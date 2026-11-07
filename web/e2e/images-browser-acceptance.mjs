/**
 * The real-browser acceptance boundary for document images: choosing Extract images when adding a document,
 * the image strip and viewer in the source reader, Keep current text with picture regions from a scanned page,
 * search for a caption, and an Ask citation that opens the image.
 *
 * `ImagesBrowserAcceptanceTest` owns the server, the temporary archive and the fakes (a fake Surya layout
 * engine, the test embedder and a local fake provider) and runs one SCENARIO per process against the same
 * server, in order: import, rescan, ask. DOCUMENT_ID is set once the import has created the document.
 *
 * Nothing here proves that real Surya finds a picture; the layout is whatever the test dictates.
 */
import { chromium } from 'playwright';
import { signedInContext } from './session.mjs';

const BASE_URL = process.env.BASE_URL;
const SCENARIO = process.env.SCENARIO;
const DOCUMENT_ID = process.env.DOCUMENT_ID;
const FILE = process.env.CHOSEN_FILE;
const CAPTION = 'Figure 1. The northern quay at dusk, photographed 1923.';
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
  const documents = page.locator(`${PANEL} .document-overview > button`).first();
  if (await documents.count() && await documents.getAttribute('aria-expanded') === 'false') await documents.click();
}

async function searchFor(page, query) {
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
  await page.waitForFunction(() => document.querySelector('#collection option[value]') !== null, null, { timeout: 20_000 });
  await page.selectOption('#mode', 'KEYWORD');
  await page.fill('#query', query);
  await page.locator('.result').first().waitFor({ timeout: 30_000 });
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
  await dialog.locator('#reading-method').waitFor({ timeout: 20_000 });
  await dialog.locator('#reading-method').selectOption('none');
  assert(await dialog.locator('#image-extraction').inputValue() === 'off', 'the collection default for Extract images is Off');
  const options = await dialog.locator('#image-extraction option').allTextContents();
  assert(options.join('|') === 'Off|Embedded images|Embedded + regions from scanned pages', `the image choices are named: ${options.join('|')}`);
  await dialog.locator('#image-extraction').selectOption('embedded');
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

  // The two pictures on page 2 are an image strip under that page's text.
  await searchFor(page, 'lanterns');
  await page.locator('.result').first().click();
  const source = page.locator('.source-pane');
  await source.waitFor({ state: 'visible', timeout: 20_000 });
  const thumbnails = source.locator('ul.image-strip li button');
  await thumbnails.nth(1).waitFor({ timeout: 20_000 });
  assert(await thumbnails.count() === 2, `page 2 shows its two pictures, got ${await thumbnails.count()}`);
  await loaded(page, '.source-pane ul.image-strip img');

  await thumbnails.first().click();
  const viewer = page.getByTestId('image-viewer');
  await viewer.waitFor({ state: 'visible', timeout: 20_000 });
  assert((await viewer.textContent()).includes('from page 2'), 'the viewer says which page the picture is on');
  assert(await viewer.locator('.caption').count() === 0, 'a picture embedded without a caption shows none');
  await loaded(page, '[data-testid="image-viewer"] img');
  assert(await viewer.getByTestId('image-box').count() === 1, 'the picture is outlined on its page');
  await page.keyboard.press('Escape');
  await viewer.waitFor({ state: 'detached', timeout: 10_000 });
  assert(await page.evaluate(() => document.activeElement?.closest('ul.image-strip') !== null), 'focus returns to the thumbnail');
  console.log('PASS import: two thumbnails under page 2, viewer opens and closes');
}

async function scenarioRescan(page) {
  assert(DOCUMENT_ID, 'DOCUMENT_ID is required');
  await openAdmin(page);
  const row = page.locator(`${PANEL} table.documents tbody tr`, { hasText: 'survey.pdf' });
  await row.first().waitFor({ timeout: 30_000 });
  await row.locator('button', { hasText: 'Details' }).first().click();
  await page.waitForSelector(`${PANEL} .document-details`, { timeout: 20_000 });
  await page.getByTestId('document-image-count').waitFor({ timeout: 20_000 });
  assert((await page.getByTestId('document-image-count').textContent()).trim() === '2', 'the details count the two pictures');
  const rescan = page.locator(`${PANEL} section.rescan`);
  await rescan.getByRole('button', { name: 'Scan again', exact: true }).click();

  const dialog = page.getByTestId('start-reading-dialog');
  await dialog.waitFor({ state: 'visible', timeout: 20_000 });
  await dialog.locator('#reading-method').waitFor({ timeout: 20_000 });
  const methods = await dialog.locator('#reading-method option').allTextContents();
  assert(methods.some((label) => /keep current text/i.test(label)), `a rescan offers Keep current text: ${methods.join('|')}`);
  await dialog.locator('#reading-method').selectOption('keep');
  assert(await dialog.getByRole('alert').textContent() === 'Choose which images to extract.', 'Keep current text needs images on');
  assert(await dialog.getByRole('button', { name: /^Extract images and keep the current text/ }).isDisabled(), 'Start is disabled while images are Off');
  const previewed = page.waitForResponse((response) => response.request().method() === 'POST' && response.url().endsWith('/ocr/preview') && (response.request().postData() ?? '').includes('"keep"'), { timeout: 20_000 });
  await dialog.locator('#image-extraction').selectOption('all');
  const preview = await (await previewed).json();
  assert(preview.snapshot?.keepsText === true && preview.snapshot?.imageExtraction === 'ALL', `the preview keeps the text: ${JSON.stringify(preview)}`);
  const started = page.waitForResponse((response) => response.request().method() === 'POST' && response.url().endsWith('/ocr/rescan'), { timeout: 20_000 });
  await dialog.getByRole('button', { name: /^Extract images and keep the current text/ }).click();
  await dialog.waitFor({ state: 'detached', timeout: 20_000 });
  const operation = await (await started).json();
  assert(typeof operation.operationId === 'string', `the rescan started: ${JSON.stringify(operation)}`);
  await page.waitForFunction(async ({ documentId, operationId }) => {
    const response = await fetch(`/api/collections/default/documents/${documentId}/ocr/operations/${encodeURIComponent(operationId)}`);
    if (!response.ok) return false;
    const body = await response.json();
    if (body.stage === 'FAILED' || body.stage === 'CANCELLED') throw new Error(`rescan ended at ${body.stage}: ${body.errorCode}`);
    return body.stage === 'COMPLETE';
  }, { documentId: DOCUMENT_ID, operationId: operation.operationId }, { timeout: 90_000 });

  const imagesNow = () => page.evaluate(async (documentId) => (await (await fetch(`/api/collections/default/documents/${documentId}/images`)).json()), DOCUMENT_ID);
  // The operation is COMPLETE once its candidate is published; the list follows the active revision.
  let list = await imagesNow();
  for (let attempt = 0; attempt < 50 && list.length < 3; attempt += 1) {
    await new Promise((resolve) => setTimeout(resolve, 200));
    list = await imagesNow();
  }
  assert(list.length === 3, `two embedded pictures and one region: ${JSON.stringify(list)}`);
  assert(list.filter((entry) => entry.caption === CAPTION).length === 1, 'the region carries the layout caption');

  // The caption is searchable, and its hit opens the picture with the region outlined on the scanned page.
  await searchFor(page, 'northern quay dusk');
  await page.locator('.result').first().click();
  const viewer = page.getByTestId('image-viewer');
  await viewer.waitFor({ state: 'visible', timeout: 20_000 });
  assert((await viewer.locator('.caption').textContent()).trim() === CAPTION, 'the viewer shows the caption');
  assert((await viewer.textContent()).includes('from page 3'), 'the region is on the scanned page 3');
  await loaded(page, '[data-testid="image-viewer"] img');
  const outline = await viewer.getByTestId('image-box').evaluate((node) => ({
    left: parseFloat(node.style.left), top: parseFloat(node.style.top), width: parseFloat(node.style.width), height: parseFloat(node.style.height),
  }));
  assert(Math.abs(outline.left - 15) < 1 && Math.abs(outline.top - 10) < 1 && Math.abs(outline.width - 70) < 1 && Math.abs(outline.height - 45) < 1,
    `the region is outlined where the layout put it: ${JSON.stringify(outline)}`);
  await page.keyboard.press('Escape');
  console.log('PASS rescan: Keep current text + regions, caption search opens the outlined region');
}

async function scenarioAsk(page) {
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
  await page.click('#tab-ask');
  await page.waitForSelector('#question', { timeout: 20_000 });
  await page.fill('#question', 'Figure photographed dusk northern');
  await page.waitForFunction(() => !document.querySelector('#panel-ask button[type="submit"]')?.disabled, null, { timeout: 20_000 });
  await page.locator('#panel-ask button[type="submit"]').click();
  const citation = page.locator('#panel-ask button.citation[aria-label^="Open source"]');
  try {
    await citation.first().waitFor({ timeout: 30_000 });
  } catch (failure) {
    throw new Error(`no citation appeared; the Ask panel says: ${(await page.textContent('#panel-ask')).replace(/\s+/g, ' ').slice(0, 1500)}`);
  }
  await citation.first().click();
  const viewer = page.getByTestId('image-viewer');
  await viewer.waitFor({ state: 'visible', timeout: 20_000 });
  assert((await viewer.locator('.caption').textContent()).trim() === CAPTION, 'the citation opens the captioned picture');
  assert((await viewer.textContent()).includes('from page 3'), 'the citation names the scanned page');
  console.log('PASS ask: the citation opens the image viewer');
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
  else if (SCENARIO === 'rescan') await scenarioRescan(page);
  else if (SCENARIO === 'ask') await scenarioAsk(page);
  else throw new Error(`unknown scenario ${SCENARIO}`);
  const relevant = errors.filter((text) => !text.includes('Failed to load resource'));
  assert(relevant.length === 0, `unexpected console errors: ${relevant.join(' | ')}`);
} finally {
  await browser.close();
}
