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

const BASE_URL = process.env.BASE_URL;
const SCENARIO = process.env.SCENARIO ?? 'primary';
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
  await citation.click();
  await page.waitForSelector('#source-heading', { timeout: 10_000 });
  await page.waitForSelector('pre.source-text', { timeout: 10_000 });
  const text = (await page.textContent('pre.source-text')) ?? '';
  assert(text.includes('Mira signed the note.'), `source viewer did not show the source text: ${JSON.stringify(text)}`);
  await page.click('button[aria-label="Close source viewer"]');
  await page.waitForSelector('#source-heading', { state: 'detached' });
}

const scenarios = {
  /** First cited question, retained-evidence follow-up with no new tools, reload/reopen, another follow-up. */
  async primary(browser) {
    const { page, consoleErrors, failedRequests } = await openReader(browser);
    await submit(page, 'Who signed the note?', { answer: 'Mira signed the note' });
    await waitForHistoryTitle(page, 'The signed note');
    assert((await answerTexts(page)).length === 1, 'the first question must expose one answer');
    await openSourceFromCitation(page);

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
    await page.fill('#limit-maxToolRounds', '1');
    await submit(page, 'Find the signature.', { answer: 'Found the signature' });
    await waitForHistoryTitle(page, 'Round note');
    const notice = (await page.textContent('#panel-investigate [role="note"]')) ?? '';
    assert(notice.includes('Tool round limit reached'), `expected the round notice, got ${JSON.stringify(notice)}`);
    assert((await answerTexts(page)).some((text) => text.includes('Found the signature')), 'the round-limited turn must answer from evidence');
  },

  /** Cancellation releases the controls and a further question succeeds. */
  async cancel(browser) {
    const { page } = await openReader(browser);
    await page.fill('#investigate-question', 'A question to cancel?');
    await page.locator('#panel-investigate form button[type="submit"]').click();
    await page.waitForSelector('#panel-investigate button:has-text("Cancel")', { timeout: 15_000 });
    // Wait until the started event has been processed and the conversation row exists, so the
    // recovery question continues this conversation rather than starting a new one.
    await page.waitForSelector('.history-row', { timeout: 15_000 });
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
};

const scenario = scenarios[SCENARIO];
if (!scenario) {
  console.error(`unknown SCENARIO ${SCENARIO}; known: ${Object.keys(scenarios).join(', ')}`);
  process.exit(2);
}

const browser = await chromium.launch({ headless: true });
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
