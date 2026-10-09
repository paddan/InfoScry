/**
 * The real-browser acceptance boundary for the OCR panels: OCR profiles, collection OCR settings, Scan again,
 * automatic publication, interruption recovery, and text history with restore.
 *
 * It drives the actual built SvelteKit reader in Chromium against a local InfoScry server whose page-reading
 * engine is a deterministic fake and whose embedder is the test fake. `OcrBrowserAcceptanceTest`
 * owns the server, the temporary archive and the fakes, and sets BASE_URL, SCENARIO, OCR_FACTS (the ids and
 * texts the archive was seeded with) and OCR_PRESENT_KEY_VARIABLE (a variable that is set in the server).
 *
 * Every scenario asserts on what the reader shows and exits non-zero on failure. A `MARK:` line is a
 * handshake: the Kotlin side does something the browser cannot (release a held reading, change the archive
 * the way another tab would) between a mark and the step that depends on it.
 *
 * Nothing here proves OCR quality, a real engine, or the GPU: the readings are whatever the test dictates.
 */
import { chromium } from 'playwright';

const BASE_URL = process.env.BASE_URL;
const SCENARIO = process.env.SCENARIO;
const FACTS = JSON.parse(process.env.OCR_FACTS ?? '{}');
const PRESENT_KEY_VARIABLE = process.env.OCR_PRESENT_KEY_VARIABLE ?? 'PATH';

if (!BASE_URL) throw new Error('BASE_URL is required');
if (!SCENARIO) throw new Error('SCENARIO is required');

const PANEL = '#admin-panel-collections';
const OCR_PANEL = '#admin-panel-ocr';
const FILENAME = 'page.png';
const HTML_NAME = '<b>Bold</b> <img src=x onerror="window.__ocrXss=true">';

let activePage = null;

function fail(message) {
  throw new Error(message);
}

function assert(condition, message) {
  if (!condition) fail(message);
}

function equal(actual, expected, message) {
  if (actual !== expected) fail(`${message}: expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
}

function mark(name) {
  console.log(`MARK: ${name}`);
}

function squash(text) {
  return (text ?? '').replace(/\s+/g, ' ').trim();
}

/* ---- Navigation ---- */

async function enterAdmin(page, tab) {
  await page.waitForSelector('#tab-admin', { timeout: 20_000 });
  await page.click('#tab-admin');
  await page.click(`#admin-tab-${tab}`);
  const selector = tab === 'ocr' ? `${OCR_PANEL} .admin-panel` : `${PANEL} .collections-panel`;
  await page.waitForSelector(selector, { timeout: 20_000 });
}

async function openPage(context, tab) {
  const page = await context.newPage();
  page.errors = [];
  page.on('pageerror', (error) => page.errors.push(String(error)));
  page.on('console', (message) => {
    if (message.type() === 'error') page.errors.push(message.text());
  });
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
  await enterAdmin(page, tab);
  activePage = page;
  return page;
}

async function openAdmin(browser, tab = 'collections') {
  const context = await browser.newContext();
  const page = await openPage(context, tab);
  return { context, page };
}

/** A reload: the archive's state has to come back from the server, not from anything the page remembered. */
async function reloadAdmin(page, tab = 'collections') {
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
  await enterAdmin(page, tab);
}

function noUnexpectedErrors(page) {
  // A refused request is also reported by the browser as a failed resource; the page's own message is the assertion.
  const relevant = page.errors.filter((text) => !text.includes('Failed to load resource'));
  assert(relevant.length === 0, `unexpected console errors: ${relevant.join(' | ')}`);
}

async function noScriptRan(page) {
  equal(await page.evaluate(() => window.__ocrXss), undefined, 'markup in a name or a reading must never run');
}

/* ---- Collections and documents ---- */

async function selectCollection(page, name) {
  await page.locator(`${PANEL} .collection-list li button`, { hasText: name }).first().click();
  await page.waitForFunction(
    (expected) => {
      const active = document.querySelector('#admin-panel-collections .collection-list li button[aria-pressed="true"]');
      return !!active && (active.querySelector('.name')?.textContent ?? '').trim() === expected;
    },
    name,
    { timeout: 20_000 },
  );
  await page.waitForFunction(
    () => {
      const text = document.querySelector('#admin-panel-collections')?.textContent ?? '';
      return !text.includes('Loading documents…') && !text.includes('Loading imports…');
    },
    null,
    { timeout: 30_000 },
  );
}

async function openDetails(page, filename) {
  const row = page.locator(`${PANEL} table.documents tbody tr`, { hasText: filename });
  await row.first().waitFor({ timeout: 30_000 });
  await row.locator('button', { hasText: 'Details' }).first().click();
  await page.waitForSelector(`${PANEL} .document-details`, { timeout: 20_000 });
  // The scans and the history read their own lists once the details are open.
  await page.waitForFunction(
    () => {
      const section = document.querySelector('#admin-panel-collections section.rescan');
      return section !== null && !(section.textContent ?? '').includes('Loading earlier scans');
    },
    null,
    { timeout: 20_000 },
  );
}

/** Admin, the Default collection, and the picture's details: what a reader does after a reload. */
async function reopenDetails(page) {
  await reloadAdmin(page);
  await selectCollection(page, 'Default');
  await openDetails(page, FILENAME);
}

const rescan = (page) => page.locator(`${PANEL} section.rescan`);
const latest = (page) => page.locator(`${PANEL} section.rescan [aria-label="Document reading status"]`);

async function latestText(page) {
  return (await latest(page).count()) === 0 ? '' : squash(await latest(page).textContent());
}

async function waitForLatest(page, fragment, timeout = 60_000) {
  await page.waitForFunction(
    (expected) => {
      const group = document.querySelector('#admin-panel-collections section.rescan [aria-label="Document reading status"]');
      return !!group && (group.textContent ?? '').replace(/\s+/g, ' ').includes(expected);
    },
    fragment,
    { timeout },
  );
}

async function previewScan(page, action = 'Scan again') {
  await rescan(page).getByRole('button', { name: action, exact: true }).click();
  const dialog = page.getByTestId('start-reading-dialog');
  await dialog.waitFor({ timeout: 20_000 });
  await dialog.getByRole('button', { name: /^(Read|Send) (at least )?\d+ pages?/ }).waitFor({ timeout: 20_000 });
  return dialog;
}

async function confirmScan(dialog, double = false) {
  const button = dialog.getByRole('button', { name: /^(Read|Send) (at least )?\d+ pages?/ });
  if (double) await button.dblclick(); else await button.click();
  await dialog.waitFor({ state: 'detached', timeout: 20_000 });
}

/* ---- The archive's own reads, as the reader's API answers them ---- */

async function searchHits(page, query) {
  const response = await page.request.get(`${BASE_URL}/api/search?collection=Default&q=${encodeURIComponent(query)}&mode=keyword`);
  assert(response.ok(), `search for ${query} answered ${response.status()}`);
  return (await response.json()).hits ?? [];
}

async function revisionCount(page) {
  const response = await page.request.get(
    `${BASE_URL}/api/collections/default/documents/${FACTS.documentId}/ocr/revisions`,
  );
  assert(response.ok(), `the history answered ${response.status()}`);
  return (await response.json()).revisions.length;
}

async function openSourceText(page) {
  await page.locator(`${PANEL} .document-details button`, { hasText: 'Open document' }).click();
  await page.waitForSelector('#source-heading', { timeout: 20_000 });
  await page.waitForSelector('pre.source-text', { timeout: 20_000 });
  const text = (await page.textContent('pre.source-text')) ?? '';
  await page.click('button[aria-label="Close source viewer"]');
  await page.waitForSelector('#source-heading', { state: 'detached' });
  return text;
}

/* ---- OCR profiles ---- */

async function fillProfile(page, { name, model, endpoint, key }) {
  await page.fill('#ocr-name', name);
  await page.fill('#ocr-model', model);
  await page.fill('#ocr-endpoint', endpoint);
  await page.fill('#ocr-key', key);
}

const profileItem = (page, name) => page.locator(`${OCR_PANEL} ul.profiles li`).filter({ hasText: name });

async function openProfileEditor(page, name) {
  await profileItem(page, name).locator('button', { hasText: 'Edit' }).click();
  await page.waitForSelector('#ocr-model', { timeout: 10_000 });
}

async function waitForScanFinished(page, fragment) {
  await waitForLatest(page, fragment, 90_000);
}

/* ---- History ---- */

const versions = (page) => page.locator(`${PANEL} ol[aria-label="Text versions"] li`);

async function openHistory(page) {
  await page.locator(`${PANEL} section.history button`, { hasText: 'Text history' }).click();
  await page.waitForSelector(`${PANEL} ol[aria-label="Text versions"]`, { timeout: 20_000 });
}

/* ---- Text history reading, OCR profile presets and catalog, and the LLM choice (local-testing-feedback 03 and 04) ---- */

const OCR_TAB = '#admin-tab-ocr';
const LLM_TAB = '#admin-tab-llm';
const NO_PROFILES = 'No OCR profiles yet.';
const CATALOG_PLACEHOLDER = 'Choose a model…';

/** The provider presets a form offers, without its placeholder. */
async function presetLabels(page, prefix) {
  return page.$$eval(`#${prefix}-preset option`, (options) =>
    options.map((option) => option.textContent.trim()).filter((text) => text !== 'Pick a preset…'));
}

async function optionTexts(page, selector) {
  return page.$$eval(selector, (options) => options.map((option) => option.textContent.trim()));
}

/** Opens Admin → OCR profiles on its new-profile form, whether or not the form was still open. */
async function openOcrForm(page) {
  await page.click(OCR_TAB);
  await page.waitForSelector('#admin-panel-ocr', { state: 'visible', timeout: 10_000 });
  const newOcr = page.locator(`${OCR_PANEL} button`, { hasText: 'New profile' });
  // The panel may still be settling after the tab change, so the button is asked for again rather than once.
  for (let attempt = 0; attempt < 4; attempt += 1) {
    if (await page.locator('#ocr-preset').isVisible()) return;
    if ((await newOcr.count()) > 0 && (await newOcr.isEnabled())) await newOcr.click();
    try {
      await page.waitForSelector('#ocr-preset', { state: 'visible', timeout: 3_000 });
      return;
    } catch {
      // try again
    }
  }
  fail('the OCR profile form did not open');
}

/** What the transcription select holds and what the profile list says, for a failure message. */
async function describeTranscription(page) {
  return page.evaluate(async () => {
    const select = document.querySelector('#collection-ocr-transcription');
    const options = select ? [...select.options].map((o) => `${o.value}|${o.textContent.trim()}|${o.selected}`) : [];
    const profiles = await (await fetch('/api/ocr/profiles')).text();
    return JSON.stringify({ options, profiles: profiles.slice(0, 1200) });
  });
}

const scenarios = {
  async profiles(browser) {
    const { context, page } = await openAdmin(browser, 'ocr');
    await page.waitForFunction(() => (document.querySelector('#admin-panel-ocr')?.textContent ?? '').includes('No OCR profiles yet.'), null, { timeout: 20_000 });

    // Create: listed with whether a key is present, never the key.
    await page.locator(`${OCR_PANEL} button`, { hasText: 'New profile' }).click();
    await fillProfile(page, { name: 'Vision reader', model: 'vision-model', endpoint: 'https://example.invalid/v1', key: PRESENT_KEY_VARIABLE });
    await page.locator(`${OCR_PANEL} button`, { hasText: 'Create profile' }).click();
    await page.waitForFunction(() => (document.querySelector('#admin-panel-ocr .flash')?.textContent ?? '').includes("'Vision reader' saved."), null, { timeout: 20_000 });
    const created = squash(await profileItem(page, 'Vision reader').textContent());
    assert(created.includes('External') && created.includes('Enabled') && created.includes('vision-model'), `the profile is listed with its scope and model, got ${created}`);
    assert(created.includes('Image support not measured'), 'an unmeasured profile says so');
    assert(created.includes(`${PRESENT_KEY_VARIABLE}: present in the server environment`), `the key variable is shown as present, got ${created}`);
    const secret = process.env[PRESENT_KEY_VARIABLE] ?? '';
    assert(secret.length > 3, 'the present-key variable must have a value for the check below to mean anything');
    const everythingShown = (await page.textContent('body')) ?? '';
    assert(!everythingShown.includes(secret), 'the value of a key variable must never reach the page');
    const listing = await page.evaluate(async () => (await fetch('/api/ocr/profiles')).text());
    assert(!listing.includes(secret), 'the profile API must never carry a key value');

    // A name that looks like markup, on a loopback endpoint that needs no key.
    await page.locator(`${OCR_PANEL} button`, { hasText: 'New profile' }).click();
    await fillProfile(page, { name: HTML_NAME, model: 'second-model', endpoint: 'http://127.0.0.1:9/v1', key: '' });
    await page.locator(`${OCR_PANEL} button`, { hasText: 'Create profile' }).click();
    await page.waitForFunction(() => document.querySelectorAll('#admin-panel-ocr ul.profiles li').length === 2, null, { timeout: 20_000 });
    const literal = profileItem(page, 'Bold');
    equal(squash(await literal.locator('strong').textContent()), HTML_NAME, 'a markup-like name is shown as typed');
    equal(await page.locator(`${OCR_PANEL} ul.profiles b, ${OCR_PANEL} ul.profiles img`).count(), 0, 'a markup-like name builds no element');
    await noScriptRan(page);
    const second = squash(await literal.textContent());
    assert(second.includes('Local'), `a loopback endpoint is local, got ${second}`);
    assert(second.includes('No key configured'), `a profile with no key variable says so, got ${second}`);
    assert(!second.includes('undefined'), `a missing field must not be printed, got ${second}`);

    // A key variable the server does not have is reported missing, by name only.
    await page.locator(`${OCR_PANEL} button`, { hasText: 'New profile' }).click();
    await fillProfile(page, { name: 'Absent key', model: 'third-model', endpoint: 'https://example.invalid/v1', key: 'INFOSCRY_ABSENT_OCR_KEY' });
    await page.locator(`${OCR_PANEL} button`, { hasText: 'Create profile' }).click();
    await page.waitForFunction(() => document.querySelectorAll('#admin-panel-ocr ul.profiles li').length === 3, null, { timeout: 20_000 });
    const third = squash(await profileItem(page, 'Absent key').textContent());
    assert(third.includes('INFOSCRY_ABSENT_OCR_KEY: missing from the server environment'), `an absent variable is reported missing, got ${third}`);

    // A second tab opens the editor on the saved revision while this one still holds the same one.
    const other = await openPage(context, 'ocr');
    await page.waitForFunction(() => document.querySelectorAll('#admin-panel-ocr ul.profiles li').length === 3, null, { timeout: 20_000 });
    await other.waitForFunction(() => document.querySelectorAll('#admin-panel-ocr ul.profiles li').length === 3, null, { timeout: 20_000 });
    await openProfileEditor(other, 'Vision reader');

    // The first tab edits and saves: that is a new revision.
    await openProfileEditor(page, 'Vision reader');
    await page.fill('#ocr-model', 'edited-vision-model');
    await page.locator(`${OCR_PANEL} button`, { hasText: 'Save changes' }).click();
    await page.waitForFunction(() => (document.querySelector('#admin-panel-ocr .flash')?.textContent ?? '').includes("'Vision reader' saved."), null, { timeout: 20_000 });
    assert(squash(await profileItem(page, 'Vision reader').textContent()).includes('edited-vision-model'), 'the edit is listed');

    // The second tab saves against the revision it loaded: refused, and its draft stays.
    await other.fill('#ocr-model', 'stale-draft-model');
    await other.locator(`${OCR_PANEL} button`, { hasText: 'Save changes' }).click();
    await other.waitForSelector(`${OCR_PANEL} [role="alert"]`, { timeout: 20_000 });
    const refusal = squash(await other.locator(`${OCR_PANEL} [role="alert"]`).textContent());
    assert(refusal.includes('This profile was changed elsewhere, so nothing was saved.'), `the stale save says why, got ${refusal}`);
    equal(await other.inputValue('#ocr-model'), 'stale-draft-model', 'the refused save keeps the draft');

    // Reloading the latest keeps the draft and names what changed elsewhere.
    await other.locator(`${OCR_PANEL} button`, { hasText: 'Reload latest' }).click();
    await other.waitForFunction(() => (document.querySelector('#admin-panel-ocr .flash')?.textContent ?? '').includes('Your unsaved input is kept'), null, { timeout: 20_000 });
    const flash = squash(await other.locator(`${OCR_PANEL} .flash`).textContent());
    assert(flash.includes('model edited-vision-model'), `the message names what changed elsewhere, got ${flash}`);
    equal(await other.inputValue('#ocr-model'), 'stale-draft-model', 'reloading the latest keeps the draft');
    equal(await other.locator(`${OCR_PANEL} [role="alert"]`).count(), 0, 'the refusal is cleared once the latest is loaded');

    // Saving now is the explicit overwrite.
    await other.locator(`${OCR_PANEL} button`, { hasText: 'Save changes' }).click();
    await other.waitForFunction(() => (document.querySelector('#admin-panel-ocr .flash')?.textContent ?? '').includes("'Vision reader' saved."), null, { timeout: 20_000 });

    // A reload shows the saved state.
    await reloadAdmin(page, 'ocr');
    await page.waitForFunction(() => document.querySelectorAll('#admin-panel-ocr ul.profiles li').length === 3, null, { timeout: 20_000 });
    assert(squash(await profileItem(page, 'Vision reader').textContent()).includes('stale-draft-model'), 'the overwrite survives a reload');
    await noScriptRan(page);
    noUnexpectedErrors(page);
    noUnexpectedErrors(other);
  },

  async 'collection-settings'(browser) {
    const { page } = await openAdmin(browser);
    await selectCollection(page, 'Alpha');
    const form = page.getByTestId('collection-ocr-settings');
    const methods = page.getByTestId('default-reading-method');
    await methods.locator('option').filter({ hasText: 'LLM: Local reader' }).waitFor({ state: 'attached' });
    await methods.selectOption({ label: 'LLM: Local reader' });
    await form.getByLabel(/language/i).fill('swe');
    await form.getByRole('button', { name: /Save/ }).click();
    await page.waitForFunction(() => (document.body.textContent ?? '').includes('saved'));
    await reloadAdmin(page);
    await selectCollection(page, 'Alpha');
    await page.waitForFunction(() => (document.querySelector('[data-testid="default-reading-method"] option:checked')?.textContent ?? '').includes('Local reader'));
    equal(await page.getByTestId('collection-ocr-settings').getByLabel(/language/i).inputValue(), 'swe', 'language persists');
    equal(JSON.stringify(await form.locator('label').allTextContents()), JSON.stringify(['OCR language', 'Default reading method']), 'only language and default method are offered');
    await selectCollection(page, 'Beta');
    equal(await page.getByTestId('collection-ocr-settings').getByLabel(/language/i).inputValue(), 'eng', 'other collection keeps its language');
    noUnexpectedErrors(page);
  },

  async 'scan-again'(browser) {
    const { page } = await openAdmin(browser);
    const admissions = [];
    page.on('request', request => { if (request.method() === 'POST' && /\/ocr\/rescan$/.test(request.url())) admissions.push(request.url()); });
    await selectCollection(page, 'Default');
    await openDetails(page, FILENAME);
    const dialog = await previewScan(page);
    assert((await dialog.textContent()).includes('1'), 'summary shows one page');
    await confirmScan(dialog, true);
    await waitForLatest(page, 'Reading');
    await reopenDetails(page);
    await waitForLatest(page, 'Reading');
    assert(await rescan(page).getByRole('button', { name: 'Cancel and start over' }).isEnabled(), 'active scan can be replaced');
    equal(admissions.length, 1, 'double click submits once');
    mark('scan-held-and-reloaded');
    await waitForScanFinished(page, 'Done');
    noUnexpectedErrors(page);
  },

  async 'publish-on-completion'(browser) {
    const { page } = await openAdmin(browser);
    await selectCollection(page, 'Default');
    await openDetails(page, FILENAME);
    await confirmScan(await previewScan(page));
    await waitForScanFinished(page, 'Done');
    equal(await openSourceText(page), FACTS.markupReading, 'new text is published as literal text');
    assert((await searchHits(page, 'zebra')).length >= 1, 'new reading is searchable');
    await noScriptRan(page);
    await openHistory(page);
    equal(await versions(page).count(), 2, 'previous version is kept');
    noUnexpectedErrors(page);
  },

  async 'retry-failed'(browser) {
    const { page } = await openAdmin(browser);
    await selectCollection(page, 'Default');
    await openDetails(page, FILENAME);
    await confirmScan(await previewScan(page));
    await waitForScanFinished(page, 'Failed');
    equal(await openSourceText(page), FACTS.firstReading, 'failure leaves old text intact');
    await reopenDetails(page);
    // Scan again starts a new explicitly confirmed run; Retry is tested at its HTTP seam.
    const scan = rescan(page).getByRole('button', { name: 'Scan again', exact: true });
    await scan.click();
    await confirmScan(page.getByTestId('start-reading-dialog'));
    await waitForScanFinished(page, 'Done');
    noUnexpectedErrors(page);
  },

  async 'cancel-start'(browser) {
    const { page } = await openAdmin(browser);
    await selectCollection(page, 'Default');
    await openDetails(page, FILENAME);
    await confirmScan(await previewScan(page));
    await waitForLatest(page, 'Reading');
    await rescan(page).getByRole('button', { name: 'Cancel and start over' }).click();
    await page.getByTestId('start-reading-dialog').waitFor({ timeout: 20_000 });
    mark('cancelled-old-text');
    await confirmScan(page.getByTestId('start-reading-dialog'));
    await waitForScanFinished(page, 'Done');
    noUnexpectedErrors(page);
  },

  async 'restart-held'(browser) {
    const { page } = await openAdmin(browser);
    await selectCollection(page, 'Default');
    await openDetails(page, FILENAME);
    await confirmScan(await previewScan(page));
    await waitForLatest(page, 'Reading');
    equal(await openSourceText(page), FACTS.firstReading, 'in-flight scan keeps old text');
    noUnexpectedErrors(page);
  },

  async 'restart-start'(browser) {
    const { page } = await openAdmin(browser);
    await selectCollection(page, 'Default');
    await openDetails(page, FILENAME);
    equal(await openSourceText(page), FACTS.firstReading, 'restart keeps old text');
    await reopenDetails(page);
    await rescan(page).getByRole('button', { name: 'Scan again', exact: true }).click();
    await confirmScan(page.getByTestId('start-reading-dialog'));
    await waitForScanFinished(page, 'Done');
    noUnexpectedErrors(page);
  },

  async 'history-restore'(browser) {
    const { page } = await openAdmin(browser);
    await selectCollection(page, 'Default');
    await openDetails(page, FILENAME);
    assert((await searchHits(page, 'second')).length >= 1, 'the rescan is what search finds before the restore');
    equal((await searchHits(page, 'first')).length, 0, 'the first reading is not searchable while it is not the active version');

    await openHistory(page);
    equal(await versions(page).count(), 2, 'both versions are listed');
    equal(await page.locator(`${PANEL} section.history .marker`).count(), 1, 'one version is marked active');
    const active = versions(page).filter({ has: page.locator('.marker') });
    assert(squash(await active.textContent()).includes('Rescan'), 'the active version is the rescan');
    const older = versions(page).filter({ hasNot: page.locator('.marker') });
    assert(squash(await older.textContent()).includes('Import'), 'the other version is the import');
    equal(await active.locator('button', { hasText: 'Restore' }).count(), 0, 'the active version offers no restore');

    // Asking asks for confirmation and states what will not happen; cancelling changes nothing.
    await older.locator('button', { hasText: 'Restore' }).click();
    const confirm = page.locator(`${PANEL} [aria-label="Confirm restore"]`);
    await confirm.waitFor({ timeout: 10_000 });
    assert(squash(await confirm.textContent()).includes('Nothing is rescanned'), 'the confirmation says no page is read again');
    await confirm.locator('button', { hasText: 'Cancel' }).click();
    await confirm.waitFor({ state: 'detached', timeout: 10_000 });
    equal(await revisionCount(page), 2, 'cancelling restores nothing');

    await older.locator('button', { hasText: 'Restore' }).click();
    await confirm.waitFor({ timeout: 10_000 });
    await confirm.locator('button', { hasText: 'Restore this version' }).click();
    await page.waitForFunction(
      () => (document.querySelector('#admin-panel-collections section.history .notice')?.textContent ?? '').includes('was restored as new version'),
      null,
      { timeout: 60_000 },
    );
    await page.waitForFunction(() => document.querySelectorAll('#admin-panel-collections ol[aria-label="Text versions"] li').length === 3, null, { timeout: 20_000 });
    equal(await page.locator(`${PANEL} section.history .marker`).count(), 1, 'exactly one version is active after the restore');
    const restored = versions(page).filter({ has: page.locator('.marker') });
    assert(squash(await restored.textContent()).includes('Restored from'), 'the active version says it is a restore');

    // Search and the source now say the restored reading.
    assert((await searchHits(page, 'first')).length >= 1, 'search finds the restored reading');
    equal((await searchHits(page, 'second')).length, 0, 'search no longer finds the replaced reading');
    equal(await openSourceText(page), FACTS.firstReading, 'the source shows the restored reading');
    noUnexpectedErrors(page);
  },

  async 'history-stale-restore'(browser) {
    const { page } = await openAdmin(browser);
    await selectCollection(page, 'Default');
    await openDetails(page, FILENAME);
    await openHistory(page);
    equal(await versions(page).count(), 2, 'both versions are listed');
    mark('history-listed');

    // The version list on screen is now out of date: wait for the change made elsewhere to be real.
    const deadline = Date.now() + 30_000;
    while ((await revisionCount(page)) < 3) {
      assert(Date.now() < deadline, 'the restore made elsewhere never happened');
      await page.waitForTimeout(200);
    }
    equal(await versions(page).count(), 2, 'the list on screen did not change by itself');

    const older = versions(page).filter({ hasNot: page.locator('.marker') });
    await older.locator('button', { hasText: 'Restore' }).click();
    const confirm = page.locator(`${PANEL} [aria-label="Confirm restore"]`);
    await confirm.waitFor({ timeout: 10_000 });
    await confirm.locator('button', { hasText: 'Restore this version' }).click();
    await page.waitForFunction(
      () => (document.querySelector('#admin-panel-collections section.history')?.textContent ?? '').includes('changed since this list was loaded'),
      null,
      { timeout: 20_000 },
    );
    const refusal = squash(await page.locator(`${PANEL} section.history [role="alert"]`).textContent());
    assert(refusal.includes('so nothing was restored'), `the refusal says nothing was restored, got ${refusal}`);
    equal(await revisionCount(page), 3, 'the stale restore added no version');

    // Reloading the history shows what changed elsewhere.
    await page.locator(`${PANEL} section.history button`, { hasText: 'Reload history' }).click();
    await page.waitForFunction(() => document.querySelectorAll('#admin-panel-collections ol[aria-label="Text versions"] li').length === 3, null, { timeout: 20_000 });
    equal(await page.locator(`${PANEL} section.history .marker`).count(), 1, 'one version is active after the reload');
    assert(
      squash(await versions(page).filter({ has: page.locator('.marker') }).textContent()).includes('Restored from'),
      'the version restored elsewhere is the active one',
    );
    noUnexpectedErrors(page);
  },

  async 'history-import-reading'(browser) {
    const { page } = await openAdmin(browser);
    await selectCollection(page, 'Default');
    await openDetails(page, FILENAME);
    await openHistory(page);
    equal(await versions(page).count(), 1, 'the picture has only its import');
    const picture = squash(await versions(page).first().textContent());
    for (const fragment of ['Tesseract (local)', 'Fill pages that have no text', 'language eng', 'Tool version: tesseract 5.5']) {
      assert(picture.includes(fragment), `the import's history names "${fragment}", got ${picture}`);
    }
    assert(picture.includes('Engine and model Tesseract (local)'), `the engine is named under Engine and model, got ${picture}`);

    // A text file is read directly, so no engine is named for it and its history says no page needed OCR.
    await reloadAdmin(page);
    await selectCollection(page, 'Default');
    await openDetails(page, 'notes.txt');
    await openHistory(page);
    equal(await versions(page).count(), 1, 'the text file has only its import');
    const notes = squash(await versions(page).first().textContent());
    assert(notes.includes('No page needed OCR'), `a direct-text import says no page needed OCR, got ${notes}`);
    assert(!notes.includes('Tesseract'), `no engine is named for a text file that OCR never read, got ${notes}`);
    noUnexpectedErrors(page);
  },

  async 'ocr-profile-catalog'(browser) {
    const { page } = await openAdmin(browser, 'ocr');
    await page.waitForFunction((text) => (document.querySelector('#admin-panel-ocr')?.textContent ?? '').includes(text), NO_PROFILES, { timeout: 20_000 });
    const providerUrl = FACTS.providerUrl ?? '';
    assert(/^http:\/\/127\.0\.0\.1:\d+$/.test(providerUrl), `the catalog is asked of a loopback fake, got ${providerUrl}`);

    // The OCR form offers the presets the LLM form offers.
    await page.locator(`${OCR_PANEL} button`, { hasText: 'New profile' }).click();
    await page.waitForFunction(() => document.querySelectorAll('#ocr-preset option').length > 1, null, { timeout: 20_000 });
    const ocrPresets = await presetLabels(page, 'ocr');
    await page.click(LLM_TAB);
    await page.waitForSelector('#admin-panel-llm', { state: 'visible', timeout: 20_000 });
    // With no LLM profile the panel opens its new-profile form itself (New is then disabled), so wait for the form.
    const newLlm = page.locator('#admin-panel-llm button', { hasText: /^New$/ });
    if (await newLlm.isEnabled()) await newLlm.click();
    await page.waitForFunction(() => document.querySelectorAll('#pf-preset option').length > 1, null, { timeout: 20_000 });
    const llmPresets = await presetLabels(page, 'pf');
    equal(JSON.stringify(ocrPresets), JSON.stringify(llmPresets), 'the OCR form offers the same provider presets as the LLM form');
    assert(ocrPresets.includes('OpenAI') && ocrPresets.includes('Custom OpenAI-compatible'), `the common presets are offered, got ${ocrPresets}`);

    // A preset fills the connection; the endpoint is then pointed at the fake before any model list is asked for.
    await openOcrForm(page);
    await page.selectOption('#ocr-preset', { label: 'OpenAI' });
    equal(await page.inputValue('#ocr-endpoint'), 'https://api.openai.com/v1', 'the OpenAI preset fills its endpoint');
    equal(await page.inputValue('#ocr-key'), 'OPENAI_API_KEY', 'the OpenAI preset names its key variable, never a value');
    await page.fill('#ocr-endpoint', providerUrl);
    await page.fill('#ocr-key', '');
    await page.locator(`${OCR_PANEL} button`, { hasText: 'Fetch models' }).click();
    await page.waitForFunction(() => document.querySelectorAll('#ocr-catalog option').length > 1, null, { timeout: 20_000 });
    const ocrModels = await page.$$eval('#ocr-catalog option', (options) =>
      options.filter((option) => option.value !== '').map((option) => ({ value: option.value, label: option.textContent.trim() })),
    );
    equal(
      JSON.stringify(ocrModels.map((model) => model.value)),
      JSON.stringify(['vendor/silent', 'vendor/vision']),
      'the OCR form lists image-capable models and never offers a text-only model',
    );
    assert(ocrModels[0].label.includes('image support unknown'), `the unknown model is labelled, got ${ocrModels[0].label}`);

    // An unknown model is labelled as such when chosen; the image-capable one saves.
    await page.selectOption('#ocr-catalog', 'vendor/silent');
    await page.waitForFunction(() => (document.querySelector('#admin-panel-ocr')?.textContent ?? '').includes('Image support unknown for this model'), null, { timeout: 10_000 });
    await page.selectOption('#ocr-catalog', 'vendor/vision');
    equal(await page.inputValue('#ocr-model'), 'vendor/vision', 'choosing a catalog model fills the model field');
    await page.fill('#ocr-name', 'Vision reader');
    await page.locator(`${OCR_PANEL} button`, { hasText: 'Create profile' }).click();
    await page.waitForFunction(() => (document.querySelector('#admin-panel-ocr .flash')?.textContent ?? '').includes("'Vision reader' saved."), null, { timeout: 20_000 });
    assert(squash(await profileItem(page, 'Vision reader').textContent()).includes('vendor/vision'), 'the saved profile carries the chosen model');

    // The LLM form is not limited the same way: the text-only model is still listed there.
    await page.click(LLM_TAB);
    await page.selectOption('#pf-preset', { label: 'Custom OpenAI-compatible' });
    await page.fill('#pf-endpoint', providerUrl);
    await page.locator('#admin-panel-llm button', { hasText: 'Fetch models' }).click();
    await page.waitForFunction(() => document.querySelectorAll('#pf-catalog option').length > 1, null, { timeout: 20_000 });
    assert((await page.locator('#pf-catalog option').evaluateAll(options => options.map(option => option.value))).includes('vendor/text-only'), 'the LLM form still lists the text-only model');
    noUnexpectedErrors(page);
  },


};

const scenario = scenarios[SCENARIO];
if (!scenario) {
  console.error(`unknown SCENARIO ${SCENARIO}; known: ${Object.keys(scenarios).join(', ')}`);
  process.exit(2);
}

const browser = await chromium.launch({
  headless: true,
  // Opt-in: a machine whose installed Chromium is not the revision this Playwright pins can name it here.
  executablePath: process.env.INFOSCRY_CHROMIUM || undefined,
});
try {
  await scenario(browser);
  console.log(`PASS ${SCENARIO}`);
} catch (error) {
  console.error(`FAIL ${SCENARIO}: ${error?.stack ?? error}`);
  if (activePage) {
    try {
      const panel = await activePage.textContent('body');
      console.error(`page text: ${JSON.stringify(squash(panel))}`);
      await activePage.screenshot({ path: `/tmp/infoscry-ocr-acceptance-${SCENARIO}.png`, fullPage: true });
      console.error(`screenshot: /tmp/infoscry-ocr-acceptance-${SCENARIO}.png`);
    } catch (dumpError) {
      console.error(`could not dump page: ${dumpError}`);
    }
  }
  process.exitCode = 1;
} finally {
  await browser.close();
}
