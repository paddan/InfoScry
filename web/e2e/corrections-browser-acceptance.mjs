/**
 * The real-browser acceptance boundary for collection corrections: correcting a misread word from the reader,
 * previewing and saving the rule, seeing the corrected text with its reading, and removing the rule again.
 */
import assert from 'node:assert/strict';
import { chromium } from 'playwright';

const base = process.env.BASE_URL;
assert(base, 'BASE_URL is required');
const browser = await chromium.launch({ headless: true, executablePath: process.env.INFOSCRY_CHROMIUM || undefined });

/** Searches until [query] finds [count] results, because a correction job publishes in the background. */
async function searchUntil(page, query, count) {
  const deadline = Date.now() + 60_000;
  for (;;) {
    await page.fill('#query', '');
    await page.fill('#query', query);
    try {
      await page.waitForFunction((expected) => document.querySelectorAll('.result').length === expected, count, { timeout: 3_000 });
      return;
    } catch (notYet) {
      if (Date.now() > deadline) throw new Error(`a search for ${query} never found ${count} results`);
    }
  }
}

/** Selects [word] in the open source text and lets go of the mouse, as a reader's drag does. */
async function selectInSource(page, word) {
  await page.evaluate((target) => {
    const pre = document.querySelector('.source-pane pre.source-text');
    const node = [...pre.childNodes].find((child) => child.nodeType === Node.TEXT_NODE && child.data.includes(target));
    const start = node.data.indexOf(target);
    const range = document.createRange();
    range.setStart(node, start);
    range.setEnd(node, start + target.length);
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    pre.dispatchEvent(new MouseEvent('mouseup', { bubbles: true }));
  }, word);
}

try {
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1600, height: 900 });
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  await page.goto(base);
  await page.waitForFunction(() => document.querySelector('#collection option[value]') !== null);
  await page.selectOption('#mode', 'KEYWORD');

  // Correct the misread surname from the reader.
  await searchUntil(page, 'spinners', 2);
  await page.locator('.result').first().click();
  await page.waitForSelector('.source-pane pre.source-text');
  await selectInSource(page, 'spinners');
  await page.getByRole('button', { name: 'Correct across collection' }).click();
  const dialog = page.getByRole('dialog');
  assert.equal(await dialog.getByLabel('Wrong').inputValue(), 'spinners');
  await dialog.getByLabel('Correct').fill('Spinnars');
  await dialog.getByRole('button', { name: 'Preview' }).click();
  await dialog.getByText('2 matches in 2 documents').waitFor({ timeout: 20_000 });
  await dialog.getByRole('button', { name: 'Save' }).click();
  await dialog.waitFor({ state: 'detached', timeout: 20_000 });
  console.log('PASS correction-saved');

  // The job rewrites both documents; the reader shows the corrected text and, on request, what was read.
  await searchUntil(page, 'Spinnars', 2);
  await page.locator('.result').first().click();
  await page.waitForFunction(() => document.querySelector('.source-pane pre.source-text')?.textContent.includes('Spinnars'), null, { timeout: 20_000 });
  await page.getByText('Corrected', { exact: true }).waitFor();
  await page.getByRole('button', { name: 'Show reading' }).click();
  await page.waitForFunction(() => document.querySelector('.source-pane pre.source-text')?.textContent.includes('spinners'), null, { timeout: 20_000 });
  await page.getByRole('button', { name: 'Show corrected text' }).click();
  console.log('PASS corrected-text-and-reading');

  // Removing the rule brings the reading back everywhere.
  await page.click('#tab-admin');
  await page.click('#admin-tab-collections');
  await page.locator('#admin-panel-collections .collection-list li button', { hasText: 'Default' }).click();
  const settings = page.locator('details.secondary-settings:has(section[aria-label="Settings for Default"])');
  if (!(await settings.evaluate((node) => node.open))) await settings.locator(':scope > summary').click();
  const corrections = page.getByRole('region', { name: 'Corrections' });
  await corrections.getByRole('table').waitFor({ timeout: 20_000 });
  await corrections.getByText('All documents follow the list').waitFor({ timeout: 60_000 });
  await corrections.getByRole('button', { name: 'Remove spinners' }).click();
  const remove = page.getByRole('dialog');
  await remove.getByText('The text returns to the reading in 2 documents.').waitFor({ timeout: 20_000 });
  await remove.getByRole('button', { name: 'Remove', exact: true }).click();
  await remove.waitFor({ state: 'detached', timeout: 20_000 });
  await corrections.getByText('All documents follow the list').waitFor({ timeout: 60_000 });
  console.log('PASS correction-removed');

  assert.deepEqual(errors, [], `no page errors: ${errors.join('\n')}`);
} finally {
  await browser.close();
}
