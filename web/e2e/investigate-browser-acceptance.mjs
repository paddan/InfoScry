/**
 * The real-browser acceptance boundary for the Investigate reader (ticket 07).
 *
 * It drives the actual built SvelteKit reader in Chromium against a local InfoScry
 * server whose LLM profile points at a scripted local fake provider. The Kotlin
 * `InvestigateBrowserAcceptanceTest` owns the server, the fake provider, and the
 * seeded collection/document/unit, and sets BASE_URL and SCENARIO for this script.
 *
 * Every scenario asserts on what the reader shows and exits non-zero on failure, so
 * the Kotlin test only has to check the process result (and its own database facts).
 */
import { chromium } from 'playwright';
import { mkdir } from 'node:fs/promises';
const SCREENSHOT_DIR = process.env.SCREENSHOT_DIR;

const BASE_URL = process.env.BASE_URL;
const SCENARIO = process.env.SCENARIO ?? 'primary';
const CATALOG_ENDPOINT = process.env.CATALOG_ENDPOINT;
const DOCUMENT_ID = process.env.DOCUMENT_ID;
const COLLECTION_ID = process.env.COLLECTION_ID;
const CITATION_UNIT_ID = process.env.CITATION_UNIT_ID;
const CITATION_REVISION_ID = process.env.CITATION_REVISION_ID;
const RESTORE_REVISION_ID = process.env.RESTORE_REVISION_ID;
if (!BASE_URL) throw new Error('BASE_URL is required');

let activePage = null;

function fail(message) {
  throw new Error(message);
}

function assert(condition, message) {
  if (!condition) fail(message);
}

async function openReader(browser) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const consoleErrors = [];
  const failedRequests = [];
  page.on('pageerror', (error) => consoleErrors.push(String(error)));
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });
  page.on('response', (response) => {
    if (response.status() >= 400) failedRequests.push(`${response.status()} ${response.url()}`);
  });
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => !!document.querySelector('#collection option'), null, { timeout: 20_000 });
  await page.click('#tab-investigate');
  // The panel needs a selected profile before a turn can be submitted.
  await page.waitForFunction(() => {
    const select = document.querySelector('#llm-profile');
    return !!select && !select.disabled && select.value !== '';
  }, null, { timeout: 20_000 });
  activePage = page;
  return { context, page, consoleErrors, failedRequests };
}

async function answerTexts(page) {
  return page.$$eval('#panel-investigate li.assistant .answer', (nodes) =>
    nodes.map((node) => node.textContent ?? ''),
  );
}

async function waitForAnswer(page, expectedFragment) {
  await page.waitForFunction(
    (fragment) =>
      Array.from(document.querySelectorAll('#panel-investigate li.assistant .answer')).some((node) =>
        (node.textContent ?? '').includes(fragment),
      ),
    expectedFragment,
    { timeout: 30_000 },
  );
}

async function waitForIdle(page) {
  await page.waitForFunction(
    () => !Array.from(document.querySelectorAll('#panel-investigate button')).some((button) => button.textContent.trim() === 'Cancel'),
    null,
    { timeout: 30_000 },
  );
}

/** Submits [question] and waits for either the expected answer or an error alert. */
async function submit(page, question, { answer = null, alert = false } = {}) {
  await page.fill('#investigate-question', question);
  await page.locator('#panel-investigate form button[type="submit"]').click();
  await page.waitForSelector('#panel-investigate button:has-text("Cancel")', { timeout: 10_000 }).catch(() => {});
  if (alert) {
    await page.waitForSelector('#panel-investigate [role="alert"]', { timeout: 30_000 });
  } else if (answer !== null) {
    await waitForAnswer(page, answer);
  }
  await waitForIdle(page);
}

async function waitForHistoryTitle(page, title) {
  await page.waitForFunction(
    (expected) => Array.from(document.querySelectorAll('.history-row')).some((row) => (row.textContent ?? '').trim() === expected),
    title,
    { timeout: 20_000 },
  );
}

async function openSourceFromCitation(page) {
  const citation = page.locator('#panel-investigate button.citation').first();
  assert((await citation.count()) > 0, 'expected a citation button');
  const pageRequests = [];
  page.on('request', (request) => {
    if (request.url().includes('/page-review') || request.url().includes('/sources/')) pageRequests.push(request.url());
  });
  await citation.click();
  await page.locator('.source-pane').waitFor({ state: 'visible', timeout: 10_000 });
  await page.locator('.page-review, .source-pane .source-text').first().waitFor({ state: 'visible', timeout: 10_000 });
  const reviewVisible = await page.locator('.page-review').isVisible();
  assert(reviewVisible, `expected a known image citation to open page review, requests=${JSON.stringify(pageRequests)}`);
  await page.waitForFunction(() => {
    const image = document.querySelector('.page-review .image-pane img');
    return image instanceof HTMLImageElement && image.complete && image.naturalWidth > 0;
  }, null, { timeout: 10_000 });
  const metadata = pageRequests.find((url) => url.includes('/page-review?'));
  const image = pageRequests.find((url) => url.includes('/page-review/image?'));
  assert(metadata && new URL(metadata).searchParams.get('revision') === CITATION_REVISION_ID,
    `historical metadata stays pinned to cited revision: ${JSON.stringify(pageRequests)}`);
  assert(image && new URL(image).searchParams.get('revision') === CITATION_REVISION_ID &&
      new URL(image).searchParams.get('ordinal') === '0' && new URL(image).searchParams.get('kind') === 'original',
    `historical image stays paired to cited revision and ordinal: ${JSON.stringify(pageRequests)}`);
  const metadataResponse = await page.evaluate(async (url) => ({ status: (await fetch(url)).status, body: await (await fetch(url)).json() }), metadata);
  const imageResponse = await page.evaluate(async (url) => (await fetch(url)).status, image);
  assert(metadataResponse.status === 200 && imageResponse === 200,
    `historical page pair returned metadata=${metadataResponse.status} image=${imageResponse}`);
  const pageMetadata = metadataResponse.body;
  assert(pageMetadata.revisionId === CITATION_REVISION_ID && pageMetadata.ordinal === 0,
    `page review metadata stays pinned to the cited image revision: ${JSON.stringify(pageMetadata)}`);
  await page.locator('.page-review').getByText('Page 1 of 1', { exact: true }).waitFor({ timeout: 10_000 });
  const text = (await page.locator('.page-review .review-text').textContent()) ?? '';
  assert(text.includes('Mira signed the note.'), `page review did not show the cited revision text: ${JSON.stringify(text)}`);
  assert(await page.locator('.page-review [aria-label="Refresh to latest reading"]').count() === 0,
    'historical citation does not offer a live reading refresh');
  console.log(`historical Investigate citation: locator=image revision=${pageMetadata.revisionId} metadata=${metadataResponse.status} image=${imageResponse} ordinal=0`);
}

async function restoreCitationToOlderReading(page) {
  assert(DOCUMENT_ID && COLLECTION_ID && CITATION_REVISION_ID && RESTORE_REVISION_ID, 'primary needs a revisable citation fixture');
  const csrfResponse = await page.request.get(`${BASE_URL}/api/session`);
  assert(csrfResponse.ok(), `session request returned ${csrfResponse.status()}`);
  const csrf = (await csrfResponse.json()).csrfToken;
  const restoreResponse = await page.request.post(
    `${BASE_URL}/api/collections/${COLLECTION_ID}/documents/${DOCUMENT_ID}/ocr/restore`,
    {
      headers: { 'Content-Type': 'application/json', 'X-InfoScry-Csrf': csrf },
      data: {
        requestId: 'investigate-browser-historical-citation-restore',
        expectedRevisionId: CITATION_REVISION_ID,
        restoreRevisionId: RESTORE_REVISION_ID,
      },
    },
  );
  assert(restoreResponse.status() === 202, `restore returned ${restoreResponse.status()}: ${await restoreResponse.text()}`);
  const restore = await restoreResponse.json();
  assert(restore.phase === 'PUBLISHED' && restore.restoredFromRevisionId === RESTORE_REVISION_ID,
    `restore did not publish the older text: ${JSON.stringify(restore)}`);
  const historyResponse = await page.request.get(
    `${BASE_URL}/api/collections/${COLLECTION_ID}/documents/${DOCUMENT_ID}/ocr/revisions?offset=0&limit=20`,
  );
  assert(historyResponse.ok(), `revision history returned ${historyResponse.status()}`);
  const history = await historyResponse.json();
  assert(history.activeRevisionId === restore.newRevisionId && history.activeRevisionId !== CITATION_REVISION_ID,
    'the citation revision must be historical before its source is opened');
  assert(history.revisions.some((revision) => revision.revisionId === CITATION_REVISION_ID && !revision.active),
    'the cited revision must still appear as a superseded history entry');
}

async function assertInvestigateMainGeometry(page) {
  const history = page.getByRole('navigation', { name: 'Conversation history', exact: true });
  const disclosure = history.locator('details');
  assert(await disclosure.getAttribute('open') === null, 'Investigate history starts closed');
  for (const width of [390, 1024, 1440, 1920]) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const geometry = await page.evaluate(() => {
      const answer = document.querySelector('#panel-investigate .answer');
      const history = document.querySelector('nav[aria-label="Conversation history"]');
      const layout = document.querySelector('.content-layout');
      const panels = layout.querySelector('.mode-panels');
      const rect = (node) => {
        const box = node.getBoundingClientRect();
        return { left: box.left, right: box.right, width: box.width, clientWidth: node.clientWidth, scrollWidth: node.scrollWidth };
      };
      return { answer: rect(answer), history: rect(history), layout: rect(layout), panels: rect(panels),
        historyInSidebar: Boolean(history.closest('aside.sidebar')), mainHistoryCount: layout.querySelectorAll('.history-sidebar').length };
    });
    assert(geometry.answer.width >= 240 && geometry.answer.scrollWidth <= geometry.answer.clientWidth + 1,
      `${width}px Investigate answer remains readable: ${JSON.stringify(geometry.answer)}`);
    assert(geometry.answer.left >= 0 && geometry.answer.right <= width, `${width}px answer fits the viewport`);
    assert(geometry.historyInSidebar && geometry.mainHistoryCount === 0, 'history belongs only to the left controls');
    assert(geometry.history.width > 0 && geometry.history.left >= 0 && geometry.history.right <= width, `${width}px sidebar history fits`);
    assert(geometry.layout.scrollWidth <= geometry.layout.clientWidth + 1 && Math.abs(geometry.panels.width - geometry.layout.width) <= 2,
      `${width}px the main task uses the width without a history column: ${JSON.stringify(geometry)}`);
    assert(await history.getByRole('button', { name: 'New conversation', exact: true }).isVisible(), 'New conversation remains available while history is closed');
    assert(!(await history.getByRole('button', { name: 'The signed note', exact: true }).isVisible()), 'closed history hides stored conversations');
    await history.locator('summary').focus();
    await page.keyboard.press('Enter');
    assert(await history.getByRole('button', { name: 'The signed note', exact: true }).isVisible(), 'opening history exposes saved conversations');
    await history.locator('summary').click();
    if (SCREENSHOT_DIR && (width === 390 || width === 1920)) {
      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await page.screenshot({ path: `${SCREENSHOT_DIR}/investigate-history-${width}.png`, fullPage: true });
    }
  }
}

async function assertInvestigateSourceGeometry(page) {
  for (const width of [390, 1024, 1440, 1920]) {
    await page.setViewportSize({ width, height: 900 });
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    if (!(await page.locator('.source-pane').isVisible())) {
      await page.locator('.workspace-context').waitFor({ state: 'visible' });
      const reopen = page.locator('.workspace-context button.reopen-source');
      if (await reopen.isVisible()) await reopen.click({ timeout: 10_000 });
      else await page.locator('#panel-investigate button.citation').first().click();
    }
    await page.locator('.source-pane').waitFor({ state: 'visible' });
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    const geometry = await page.evaluate(() => {
      const source = document.querySelector('.source-pane');
      const context = document.querySelector('.workspace-context');
      const workspace = document.querySelector('.source-workspace');
      const sourceBox = source.getBoundingClientRect();
      const contextBox = context.getBoundingClientRect();
      return {
        layout: workspace.dataset.layout,
        mode: workspace.dataset.mode,
        source: { left: sourceBox.left, right: sourceBox.right, width: sourceBox.width, clientWidth: source.clientWidth, scrollWidth: source.scrollWidth },
        context: { right: contextBox.right, hidden: context.hidden },
      };
    });
    assert(geometry.source.width > 300 && geometry.source.right <= width && geometry.source.left >= 0,
      `${width}px historical source remains visible and bounded: ${JSON.stringify(geometry)}`);
    assert(geometry.source.scrollWidth <= geometry.source.clientWidth + 1,
      `${width}px historical source text has no horizontal overflow: ${JSON.stringify(geometry.source)}`);
    if (geometry.layout === 'split') {
      assert(geometry.mode === 'split' && geometry.source.width > 350 && geometry.source.width <= 560 && geometry.context.right < geometry.source.left,
        `${width}px split view keeps a readable source beside separate context: ${JSON.stringify(geometry)}`);
    } else {
      assert(await page.locator('.source-pane button[aria-label="Back to results"]').isVisible(),
        `${width}px narrow source exposes its Back control`);
      await page.locator('.source-pane button[aria-label="Back to results"]').click();
      await page.locator('.workspace-context').waitFor({ state: 'visible' });
      assert(await page.locator('#panel-investigate .answer').isVisible(), `${width}px Back restores the answer context`);
      assert(await page.locator('.workspace-context button.reopen-source').isVisible(), `${width}px Back exposes Open selected source`);
    }
    if (geometry.layout === 'split') {
      await page.locator('.source-pane button[aria-label="Close source viewer"]').click();
      await page.locator('.source-pane').waitFor({ state: 'hidden' });
    }
  }
  if (!(await page.locator('.source-pane').isVisible())) {
    const reopen = page.locator('.workspace-context button.reopen-source');
    if (await reopen.isVisible()) await reopen.click();
  }
  if (await page.locator('.source-pane button[aria-label="Back to results"]').isVisible()) {
    await page.locator('.source-pane button[aria-label="Back to results"]').click();
  } else if (await page.locator('.source-pane button[aria-label="Close source viewer"]').isVisible()) {
    await page.locator('.source-pane button[aria-label="Close source viewer"]').click();
  }
  await page.locator('.source-pane').waitFor({ state: 'hidden' });
}

const scenarios = {
  /** First cited question, retained-evidence follow-up with no new tools, reload/reopen, another follow-up. */
  async primary(browser) {
    const { page, consoleErrors, failedRequests } = await openReader(browser);
    await submit(page, 'Who signed the note?', { answer: 'Mira signed the note' });
    await waitForHistoryTitle(page, 'The signed note');
    assert((await answerTexts(page)).length === 1, 'the first question must expose one answer');
    await restoreCitationToOlderReading(page);
    await assertInvestigateMainGeometry(page);
    await openSourceFromCitation(page);
    await assertInvestigateSourceGeometry(page);

    await submit(page, 'When was it signed?', { answer: 'The note was signed' });
    assert((await answerTexts(page)).length === 2, 'the follow-up must add one answer');

    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.click('#tab-investigate');
    await page.waitForFunction(
      () => document.querySelectorAll('#panel-investigate li.assistant .answer').length === 2,
      null,
      { timeout: 20_000 },
    );
    assert((await answerTexts(page)).length === 2, 'reopening must restore both adopted answers');

    await submit(page, 'Any third fact?', { answer: 'A third answer' });
    assert((await answerTexts(page)).length === 3, 'a follow-up after reopening must add exactly one answer');
    // A missing favicon is not a product failure; every other console error is.
    const relevantErrors = consoleErrors.filter((text) => !text.includes('Failed to load resource'));
    assert(relevantErrors.length === 0, `unexpected console errors: ${relevantErrors.join(' | ')}`);
    console.log(`failed requests (non-fatal): ${failedRequests.join(', ') || 'none'}`);
  },

  /** A draft with an invalid citation is corrected once and appears as a single adopted answer. */
  async correction(browser) {
    const { page } = await openReader(browser);
    await submit(page, 'Who signed the note?', { answer: 'corrected cites' });
    await waitForHistoryTitle(page, 'Corrected note');
    const answers = await answerTexts(page);
    assert(answers.length === 1, `correction must leave one answer, got ${answers.length}`);
    assert(answers[0].includes('corrected cites'), `the adopted answer must be the corrected one: ${answers[0]}`);
    assert(!(await page.textContent('#panel-investigate')).includes('draft cites'), 'the superseded draft must not be visible');

    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.click('#tab-investigate');
    await page.waitForFunction(
      () => document.querySelectorAll('#panel-investigate li.assistant .answer').length === 1,
      null,
      { timeout: 20_000 },
    );
    assert(!(await page.textContent('#panel-investigate')).includes('draft cites'), 'the draft must stay hidden after reopen');
  },

  /** An HTTP rejection is a visible failure, and the next question still works. */
  async rejection(browser) {
    const { page } = await openReader(browser);
    await submit(page, 'Who signed the note?', { alert: true });
    await waitForHistoryTitle(page, 'Rejected note');
    const alert = (await page.textContent('#panel-investigate [role="alert"]')) ?? '';
    assert(alert.trim() !== '', 'the rejection must show an understandable message');

    await submit(page, 'Try again?', { answer: 'Recovered answer' });
    assert((await answerTexts(page)).some((text) => text.includes('Recovered answer')), 'a question after a rejection must succeed');
  },

  /** A completion with no evidence finishes normally and releases the controls. */
  async emptyEvidence(browser) {
    const { page } = await openReader(browser);
    await submit(page, 'Question without sources?', { answer: 'No sources were needed.' });
    await waitForHistoryTitle(page, 'Empty note');
    assert((await page.locator('#panel-investigate [role="alert"]').count()) === 0, 'an omitted evidence field must not be an error');

    await page.reload({ waitUntil: 'domcontentloaded' });
    await page.click('#tab-investigate');
    await page.waitForFunction(
      () => document.querySelectorAll('#panel-investigate li.assistant .answer').length === 1,
      null,
      { timeout: 20_000 },
    );
  },

  /** An equivalent repeated tool call stops research with a visible notice and a cited answer. */
  async repeatLimit(browser) {
    const { page } = await openReader(browser);
    await submit(page, 'Find the signature.', { answer: 'Found the signature' });
    await waitForHistoryTitle(page, 'Repeat note');
    const notice = (await page.textContent('#panel-investigate [role="note"]')) ?? '';
    assert(notice.includes('Identical tool call repeated'), `expected the repeat notice, got ${JSON.stringify(notice)}`);
    assert((await answerTexts(page)).some((text) => text.includes('Found the signature')), 'the repeat-limited turn must answer from evidence');

    await submit(page, 'And now?', { answer: 'A recovered follow-up' });
    assert((await answerTexts(page)).some((text) => text.includes('A recovered follow-up')), 'a follow-up after a limit must work');
  },

  /** The configured round limit stops research with a visible notice and a cited answer. */
  async roundLimit(browser) {
    const { page } = await openReader(browser);
    await page.selectOption('#investigate-effort', 'LOW');
    await submit(page, 'Find the signature.', { answer: 'Found the signature' });
    await waitForHistoryTitle(page, 'Round note');
    const notice = (await page.textContent('#panel-investigate [role="note"]')) ?? '';
    assert(notice.includes('Tool round limit reached'), `expected the round notice, got ${JSON.stringify(notice)}`);
    assert((await answerTexts(page)).some((text) => text.includes('Found the signature')), 'the round-limited turn must answer from evidence');
  },

  async effortHints(browser) {
    const { page } = await openReader(browser);
    await page.selectOption('#llm-profile', { label: 'native investigator' });
    await page.selectOption('#investigate-effort', 'HIGH');
    assert(await page.locator('.resolved-limits').count() === 0, 'no guessed caps before starting');
    await submit(page, 'First effort question.', { answer: 'First effort answer' });
    await waitForHistoryTitle(page, 'First effort');
    await page.getByText('Up to 7 tool rounds, 9 tool calls, 90 seconds per question.', { exact: true }).waitFor();
    await page.getByRole('button', { name: 'New conversation', exact: true }).click();
    await page.selectOption('#llm-profile', { label: 'investigator' });
    assert(await page.locator('.resolved-limits').count() === 0, 'changing profile clears caps');
    await submit(page, 'Second effort question.', { answer: 'Second effort answer' });
    await waitForHistoryTitle(page, 'Second effort');
    await page.getByText('Up to 50 tool rounds, 75 tool calls, 1200 seconds per question.', { exact: true }).waitFor();
    await page.selectOption('#investigate-effort', 'LOW');
    assert(await page.locator('.resolved-limits').count() === 0, 'changing effort clears caps');
  },

  /** Cancellation releases the controls and a further question succeeds. */
  async cancel(browser) {
    const { page } = await openReader(browser);
    await page.fill('#investigate-question', 'A question to cancel?');
    await page.locator('#panel-investigate form button[type="submit"]').click();
    await page.waitForSelector('#panel-investigate button:has-text("Cancel")', { timeout: 15_000 });
    // Wait until the started event has been processed and the conversation row exists, so the
    // recovery question continues this conversation rather than starting a new one.
    await page.waitForSelector('.history-row', { state: 'attached', timeout: 15_000 });
    await page.click('#panel-investigate button:has-text("Cancel")');
    await waitForIdle(page);
    await page.waitForFunction(
      () => {
        const panel = document.querySelector('#panel-investigate');
        return !!panel && /cancel/i.test(panel.textContent ?? '');
      },
      null,
      { timeout: 15_000 },
    );

    await submit(page, 'After cancelling?', { answer: 'Recovered after cancel' });
    assert((await answerTexts(page)).some((text) => text.includes('Recovered after cancel')), 'a question after cancellation must succeed');
  },

  /**
   * An unmeasured profile is marked in the select and refused with the explanation and the remedy; Admin
   * measures it with "Check tool calling" against the fake provider; the reader then runs a question.
   */
  async unmeasuredProfile(browser) {
    const { page, consoleErrors } = await openReader(browser);
    const selectedLabel = await page.$eval('#llm-profile', (select) => select.selectedOptions[0]?.textContent ?? '');
    assert(selectedLabel.includes('tool calling not measured'), `the select must mark an unmeasured profile: ${JSON.stringify(selectedLabel)}`);
    const remedy = (await page.textContent('.profile-remedy')) ?? '';
    assert(remedy.includes('Check tool calling'), `the sidebar must name the remedy before any question: ${JSON.stringify(remedy)}`);

    await submit(page, 'Who signed the note?', { alert: true });
    const alert = (await page.textContent('#panel-investigate [role="alert"]')) ?? '';
    assert(
      alert.includes("the profile 'investigator' has not been measured for tool calling"),
      `the refusal must explain the profile state: ${JSON.stringify(alert)}`,
    );
    assert(alert.includes('Check tool calling'), `the refusal must name the remedy: ${JSON.stringify(alert)}`);

    await page.click('#tab-admin');
    await page.click('#admin-tab-llm');
    await page.waitForSelector('select[aria-label="Profile"]', { timeout: 20_000 });
    // The catalog belongs to an editable draft. Open a new draft, point it at the deterministic local
    // fake, and choose its listed model so the test exercises the live catalog and suggested name.
    assert(CATALOG_ENDPOINT, 'CATALOG_ENDPOINT must point at this test’s local fake provider');
    await page.getByRole('button', { name: 'New', exact: true }).click();
    const catalogResponsePromise = page.waitForResponse((response) => {
      const url = new URL(response.url());
      return url.pathname === '/api/llm/catalog' && url.searchParams.get('endpoint') === CATALOG_ENDPOINT;
    }, { timeout: 20_000 });
    await page.fill('#pf-endpoint', CATALOG_ENDPOINT);
    await page.locator('#pf-model').focus();
    const catalogResponse = await catalogResponsePromise;
    assert(catalogResponse.status() === 200, `live catalog request returned ${catalogResponse.status()}`);
    const catalogBody = await catalogResponse.json();
    assert(catalogBody.models?.some((model) => model.id === 'model'), `catalog response did not list the fake model: ${JSON.stringify(catalogBody)}`);
    console.log(`catalog UI request: GET /api/llm/catalog status=${catalogResponse.status()} endpoint=${new URL(catalogResponse.url()).searchParams.get('endpoint')} modelCount=${catalogBody.models.length}`);
    await page.locator('#pf-catalog').waitFor({ timeout: 20_000 });
    const listedModel = await page.locator('#pf-catalog option:not([disabled])').first().getAttribute('value');
    assert(listedModel, 'the live model catalog must offer at least one model');
    await page.selectOption('#pf-catalog', listedModel);
    assert((await page.inputValue('#pf-name')).includes(listedModel), 'choosing a catalog model suggests its name');
    await page.getByRole('button', { name: 'Cancel', exact: true }).click();
    await page.click('button:has-text("Check tool calling")');
    await page.waitForFunction(
      () => Array.from(document.querySelectorAll('[role="status"]')).some((node) => (node.textContent ?? '').includes('Tool calling: supported')),
      null,
      { timeout: 30_000 },
    );

    await page.click('#tab-investigate');
    await page.waitForFunction(
      () => {
        const select = document.querySelector('#llm-profile');
        const option = select?.selectedOptions?.[0];
        return !!option && option.textContent.trim() === 'investigator';
      },
      null,
      { timeout: 20_000 },
    );
    await submit(page, 'Who signed the note?', { answer: 'Mira signed the note' });
    await waitForHistoryTitle(page, 'Measured note');
    assert((await answerTexts(page)).length === 1, 'the question after measuring must add exactly one answer');
    const relevantErrors = consoleErrors.filter((text) => !text.includes('Failed to load resource'));
    assert(relevantErrors.length === 0, `unexpected console errors: ${relevantErrors.join(' | ')}`);
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
      const panel = await activePage.textContent('#panel-investigate');
      console.error(`panel text: ${JSON.stringify(panel)}`);
      await activePage.screenshot({ path: `/tmp/infoscry-browser-acceptance-${SCENARIO}.png`, fullPage: true });
      console.error(`screenshot: /tmp/infoscry-browser-acceptance-${SCENARIO}.png`);
    } catch (dumpError) {
      console.error(`could not dump page: ${dumpError}`);
    }
  }
  process.exitCode = 1;
} finally {
  await browser.close();
}
