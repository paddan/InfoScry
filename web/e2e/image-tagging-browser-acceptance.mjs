/**
 * The real-browser acceptance boundary for image tagging: a picture is imported with an image-capable profile,
 * its description and tags are shown, a `possibly` name is confirmed by an editor, the *People* filter finds the
 * picture, and an Ask citation opens it.
 *
 * `ImageTaggingBrowserAcceptanceTest` owns the server, the temporary archive and the fakes (the test embedder
 * and one local fake provider serving both the analysis and Ask) and runs one SCENARIO per process against the
 * same server, in order: import, confirm, ask. DOCUMENT_ID is set once the import has created the document.
 *
 * Nothing here proves a real vision model's quality; the answers are whatever the fake dictates.
 */
import { chromium } from 'playwright';
import { signedInContext } from './session.mjs';

const BASE_URL = process.env.BASE_URL;
const SCENARIO = process.env.SCENARIO;
const DOCUMENT_ID = process.env.DOCUMENT_ID;
const FILE = process.env.CHOSEN_FILE;
const DESCRIPTION = 'A red car by a quay in the sun.';
const TAG_WITH_NAME = 'man: possibly Jeffrey Epstein';
if (!BASE_URL || !SCENARIO) throw new Error('BASE_URL and SCENARIO are required');

const PANEL = '#admin-panel-collections';

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function openAdmin(page) {
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForSelector('#tab-admin', { timeout: 20_000 });
  await page.click('#tab-admin');
  await page.click('#admin-tab-collections');
  await page.waitForSelector(`${PANEL} .collections-panel`, { timeout: 20_000 });
  await page.locator(`${PANEL} .collection-list li button`, { hasText: 'Default' }).first().click();
  await page.waitForFunction(
    () => {
      const text = document.querySelector('#admin-panel-collections')?.textContent ?? '';
      return !text.includes('Loading documents…') && !text.includes('Loading imports…');
    },
    null,
    { timeout: 30_000 },
  );
}

async function searchFor(page, query, picture = '') {
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.querySelector('#collection option[value]') !== null, null, { timeout: 20_000 });
  await page.selectOption('#mode', 'KEYWORD');
  if (picture !== '') await page.selectOption('#picture-filter', picture);
  await page.fill('#query', query);
  await page.locator('.result').first().waitFor({ timeout: 30_000 });
}

/** Searches for [query] and opens the picture: a picture hit opens its viewer directly, a text hit needs the strip. */
async function openPicture(page, query, picture = '') {
  await searchFor(page, query, picture);
  await page.locator('.result').first().click();
  const viewer = page.getByTestId('image-viewer');
  const direct = await viewer.waitFor({ state: 'visible', timeout: 5_000 }).then(() => true).catch(() => false);
  if (!direct) {
    const source = page.locator('.source-pane');
    await source.waitFor({ state: 'visible', timeout: 20_000 });
    const thumbnail = source.locator('ul.image-strip li button').first();
    await thumbnail.waitFor({ timeout: 20_000 });
    await loaded(page, '.source-pane ul.image-strip img');
    await thumbnail.click();
    await viewer.waitFor({ state: 'visible', timeout: 20_000 });
  }
  await loaded(page, '[data-testid="image-viewer"] img.full');
  return viewer;
}

async function loaded(page, selector) {
  await page.waitForFunction(
    (css) => {
      const nodes = [...document.querySelectorAll(css)];
      return nodes.length > 0 && nodes.every((node) => node.complete && node.naturalWidth > 0);
    },
    selector,
    { timeout: 20_000 },
  );
}

async function scenarioImport(page) {
  await openAdmin(page);
  await page.locator(`${PANEL} button`, { hasText: 'Add documents' }).first().click();
  await page.waitForSelector('.import-panel', { timeout: 20_000 });
  await page.locator('.import-panel input[aria-label="Files to upload"]').setInputFiles(FILE);
  await page.waitForSelector('.import-panel .paths li', { timeout: 20_000 });
  await page.locator('.import-panel button', { hasText: 'Import' }).first().click();

  const dialog = page.getByTestId('start-reading-dialog');
  await dialog.waitFor({ state: 'visible', timeout: 20_000 });
  await dialog.locator('#reading-method').selectOption('none');
  await dialog.locator('#image-extraction').selectOption('embedded');
  const profiles = await dialog.getByTestId('describe-images').locator('option').allTextContents();
  assert(profiles.includes('Fake vision'), `the checked image profile is offered: ${profiles.join('|')}`);
  await dialog.getByTestId('describe-images').selectOption({ label: 'Fake vision' });
  await dialog.getByTestId('identify-appearance').check();

  const summary = dialog.locator('section.summary');
  await summary.getByText(/1 image will be described/).waitFor({ timeout: 20_000 });
  const confirm = dialog.getByRole('button', { name: /^Import with the document.s own text/ });
  await confirm.waitFor({ timeout: 20_000 });
  await confirm.click();
  await dialog.waitFor({ state: 'detached', timeout: 20_000 });
  await page.waitForFunction(
    () => /Import complete|Import failed|Import cancelled/.test(document.querySelector('.import-panel')?.textContent ?? ''),
    null,
    { timeout: 90_000 },
  );
  assert((await page.textContent('.import-panel')).includes('Import complete'), 'the import finished');

  // The picture's own document is one analysed image: its description and tags are searchable.
  const viewer = await openPicture(page, 'car');
  assert((await viewer.getByTestId('image-description').textContent()).trim() === DESCRIPTION, 'the viewer shows the description');
  assert((await viewer.textContent()).includes(TAG_WITH_NAME), `the appearance name is marked possibly: ${await viewer.textContent()}`);
  console.log('PASS import: the picture is described and its tag reads possibly');
}

async function scenarioConfirm(page) {
  assert(DOCUMENT_ID, 'DOCUMENT_ID is required');
  const viewer = await openPicture(page, 'car');
  const tagged = viewer.locator('.tag-item', { hasText: 'Jeffrey Epstein' }).first();
  await tagged.waitFor({ timeout: 20_000 });
  assert((await tagged.textContent()).includes('possibly'), 'the suggestion starts as uncertain');
  await tagged.getByRole('button', { name: 'Confirm' }).click();
  await viewer.locator('.tag-label', { hasText: 'man: Jeffrey Epstein' }).waitFor({ timeout: 20_000 });
  assert((await viewer.getByTestId('image-description').textContent()).trim() === DESCRIPTION, 'the description survives the edit');
  assert((await viewer.textContent()).includes('Confirmed by'), 'the edit names who confirmed it');

  // The *People* filter finds the confirmed picture, and only it.
  await searchFor(page, 'Jeffrey', 'people');
  const results = await page.locator('.result').allTextContents();
  assert(results.some((text) => text.includes('Jeffrey Epstein')), `the People filter returns the picture: ${results.join(' | ')}`);
  console.log('PASS confirm: the tag becomes fact, names the editor, and the People filter finds it');
}

async function scenarioAsk(page) {
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
  await page.click('#tab-ask');
  await page.waitForSelector('#question', { timeout: 20_000 });
  await page.fill('#question', 'Who is by the red car?');
  await page.waitForFunction(() => !document.querySelector('#panel-ask button[type="submit"]')?.disabled, null, { timeout: 20_000 });
  await page.locator('#panel-ask button[type="submit"]').click();
  const citation = page.locator('#panel-ask button.citation[aria-label^="Open source"]');
  try {
    await citation.first().waitFor({ timeout: 30_000 });
  } catch {
    throw new Error(`no citation appeared; the Ask panel says: ${(await page.textContent('#panel-ask')).replace(/\s+/g, ' ').slice(0, 1500)}`);
  }
  await citation.first().click();
  const viewer = page.getByTestId('image-viewer');
  await viewer.waitFor({ state: 'visible', timeout: 20_000 });
  assert((await viewer.getByTestId('image-description').textContent()).trim() === DESCRIPTION, 'the citation opens the described picture');
  assert((await viewer.textContent()).includes('man: Jeffrey Epstein'), 'the picture citation carries its confirmed tag');
  console.log('PASS ask: the citation opens the picture with its tags');
}

const browser = await chromium.launch({ headless: true, executablePath: process.env.INFOSCRY_CHROMIUM || undefined });
try {
  const context = await signedInContext(browser);
  const page = await context.newPage();
  await page.setViewportSize({ width: 1600, height: 1000 });
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  page.on('console', (message) => { if (message.type() === 'error') errors.push(message.text()); });
  if (SCENARIO === 'import') await scenarioImport(page);
  else if (SCENARIO === 'confirm') await scenarioConfirm(page);
  else if (SCENARIO === 'ask') await scenarioAsk(page);
  else throw new Error(`unknown scenario ${SCENARIO}`);
  const relevant = errors.filter((text) => !text.includes('Failed to load resource'));
  assert(relevant.length === 0, `unexpected console errors: ${relevant.join(' | ')}`);
} finally {
  await browser.close();
}
