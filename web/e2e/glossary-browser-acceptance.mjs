/**
 * The real-browser acceptance boundary for the collection glossary: adding a group in the collection's settings,
 * seeing a search for one name find the other and say so, and removing the group again.
 */
import assert from 'node:assert/strict';
import { chromium } from 'playwright';

const base = process.env.BASE_URL;
assert(base, 'BASE_URL is required');
const browser = await chromium.launch({ headless: true, executablePath: process.env.INFOSCRY_CHROMIUM || undefined });

/** Searches until [query] finds [count] results. */
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

async function openGlossary(page) {
  await page.click('#tab-admin');
  await page.click('#admin-tab-collections');
  await page.locator('#admin-panel-collections .collection-list li button', { hasText: 'Default' }).click();
  const settings = page.locator('details.secondary-settings:has(section[aria-label="Settings for Default"])');
  if (!(await settings.evaluate((node) => node.open))) await settings.locator(':scope > summary').click();
  const glossary = page.getByRole('region', { name: 'Glossary' });
  await glossary.getByRole('button', { name: 'Add group' }).waitFor({ timeout: 20_000 });
  return glossary;
}

try {
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1600, height: 900 });
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  await page.goto(base);
  await page.waitForFunction(() => document.querySelector('#collection option[value]') !== null);
  await page.selectOption('#mode', 'KEYWORD');
  await searchUntil(page, 'Sthlm', 1);
  console.log('PASS one-name-finds-one');

  // Say in the collection's glossary that the two names mean the same.
  let glossary = await openGlossary(page);
  await glossary.getByRole('button', { name: 'Add group' }).click();
  const dialog = page.getByRole('dialog');
  await dialog.getByLabel('Term 1').fill('Stockholm');
  await dialog.getByLabel('Term 2').fill('Sthlm');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await dialog.waitFor({ state: 'detached', timeout: 20_000 });
  await glossary.getByRole('listitem').filter({ hasText: 'Stockholm' }).waitFor({ timeout: 20_000 });
  console.log('PASS group-saved');

  // A search for one name now finds the other, says so and marks it.
  await page.click('#tab-search');
  await searchUntil(page, 'Sthlm', 2);
  await page.getByText('Also searched: Stockholm').waitFor({ timeout: 20_000 });
  const marked = await page.locator('.results mark').allTextContents();
  assert(marked.includes('Stockholm'), `Stockholm is marked: ${marked.join(', ')}`);
  console.log('PASS other-name-found');

  // Removing the group brings the search back to the name as written.
  glossary = await openGlossary(page);
  await glossary.getByRole('button', { name: 'Remove Stockholm' }).click();
  await glossary.getByText('No glossary groups yet.').waitFor({ timeout: 20_000 });
  await page.click('#tab-search');
  await searchUntil(page, 'Sthlm', 1);
  assert.equal(await page.getByText(/Also searched/).count(), 0);
  console.log('PASS group-removed');

  assert.deepEqual(errors, [], `no page errors: ${errors.join('\n')}`);
} finally {
  await browser.close();
}
