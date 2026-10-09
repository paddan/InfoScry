/**
 * The real-browser acceptance boundary for the OCR panels: image reading for LLM profiles, collection OCR settings, Scan again,
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

if (!BASE_URL) throw new Error('BASE_URL is required');
if (!SCENARIO) throw new Error('SCENARIO is required');

const PANEL = '#admin-panel-collections';
const FILENAME = 'page.png';

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
  const selector = tab === 'llm' ? '#admin-panel-llm .admin-panel' : `${PANEL} .collections-panel`;
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

/* ---- OCR ---- */

async function waitForScanFinished(page, fragment) {
  await waitForLatest(page, fragment, 90_000);
}

/* ---- History ---- */

const versions = (page) => page.locator(`${PANEL} ol[aria-label="Text versions"] li`);

async function openHistory(page) {
  await page.locator(`${PANEL} section.history button`, { hasText: 'Text history' }).click();
  await page.waitForSelector(`${PANEL} ol[aria-label="Text versions"]`, { timeout: 20_000 });
}


const scenarios = {
  async 'llm-image-reading'(browser) {
    // One LLM profile whose endpoint is a loopback fake that answers the synthetic image check.
    const { page } = await openAdmin(browser, 'llm');
    assert(await page.locator('#admin-tab-ocr').count() === 0, 'OCR profiles are no longer a separate admin section');
    await page.waitForSelector('#admin-panel-llm select[aria-label="Profile"]', { timeout: 20_000 });
    await page.waitForFunction(() => (document.querySelector('#admin-panel-llm')?.textContent ?? '').includes('Image reading for OCR: not checked'), null, { timeout: 20_000 });

    // The check offers the profile for OCR and measures it with the test image.
    await page.locator('#admin-panel-llm button', { hasText: 'Check image reading' }).click();
    await page.waitForFunction(() => (document.querySelector('#admin-panel-llm')?.textContent ?? '').includes('Image reading for OCR: confirmed'), null, { timeout: 30_000 });
    const confirmed = squash(await page.locator('#admin-panel-llm .flash').textContent());
    assert(confirmed.includes('Image reading confirmed'), `the check reports success, got ${confirmed}`);

    // The profile is now a reading method a collection can choose, and it was not before the check.
    const methods = await page.evaluate(async (collection) => {
      const collections = await (await fetch('/api/collections')).json();
      const id = collections.collections.find((entry) => entry.name === collection).id;
      return (await (await fetch(`/api/collections/${id}/reading-methods`)).json()).methods;
    }, 'Default');
    const vision = methods.find((method) => method.label.includes('Vision reader'));
    assert(vision !== undefined, `the profile is listed as a reading method, got ${JSON.stringify(methods)}`);
    equal(vision.available, true, 'a profile that passed the image check can be chosen for OCR');
    noUnexpectedErrors(page);
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
