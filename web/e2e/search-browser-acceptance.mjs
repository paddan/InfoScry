import assert from 'node:assert/strict';
import { chromium } from 'playwright';

const base = process.env.BASE_URL;
assert(base, 'BASE_URL is required');
const browser = await chromium.launch({
  headless: true,
  // Opt-in: a machine whose installed Chromium is not the revision this Playwright pins can name it here.
  executablePath: process.env.INFOSCRY_CHROMIUM || undefined,
});
try {
  const page = await browser.newPage();
  await page.setViewportSize({ width: 1600, height: 900 });
  const errors = [];
  page.on('pageerror', (error) => errors.push(String(error)));
  await page.goto(base);
  await page.waitForFunction(() => document.querySelector('#collection option[value]') !== null);
  await page.selectOption('#mode', 'KEYWORD');
  await page.click('summary:text("Advanced search filters")');

  // Delay a genuine server response so an older unfiltered reply arrives after the filter's reply.
  let releaseOld;
  let firstSeen;
  let oldDelivered;
  const oldHold = new Promise((resolve) => { releaseOld = resolve; });
  const firstRequest = new Promise((resolve) => { firstSeen = resolve; });
  const delivered = new Promise((resolve) => { oldDelivered = resolve; });
  let held = false;
  await page.route('**/api/search?**', async (route) => {
    const url = new URL(route.request().url());
    if (!held && url.searchParams.get('q') === 'budget mark' && !url.searchParams.has('path')) {
      held = true;
      const response = await route.fetch();
      firstSeen();
      await oldHold;
      await route.fulfill({ response });
      oldDelivered();
    } else {
      await route.continue();
    }
  });
  await page.fill('#query', 'budget mark');
  await firstRequest;
  await page.fill('#path-contains', 'reports');
  await page.waitForFunction(() => {
    const titles = [...document.querySelectorAll('.result-title')].map((node) => node.textContent);
    return titles.length === 1 && titles[0] === 'report.txt';
  });
  releaseOld();
  await delivered;
  await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
  assert.deepEqual(await page.locator('.result-title').allTextContents(), ['report.txt']);
  assert.deepEqual((await page.locator('.results mark').allTextContents()).sort(), ['budget', 'mark']);
  assert.equal(await page.locator('.results img').count(), 0, 'source HTML must remain inert');
  assert.equal(await page.evaluate(() => window.__searchXss), undefined);

  // The source occupies the right-hand reading area while Search stays legible on the left.
  await page.locator('.result').first().click();
  await page.waitForSelector('.source-sheet');
  const desktopLayout = await page.evaluate(() => {
    const source = document.querySelector('.source-sheet').getBoundingClientRect();
    const content = document.querySelector('.mode-panels').getBoundingClientRect();
    return { sourceLeft: source.left, sourceRight: source.right, sourceWidth: source.width, contentRight: content.right };
  });
  assert(desktopLayout.sourceWidth > 600, 'desktop source preview should be wide enough to read');
  assert(desktopLayout.contentRight < desktopLayout.sourceLeft, 'Search and preview should occupy separate columns');
  assert.equal(desktopLayout.sourceRight, 1600);
  await page.getByRole('button', { name: 'Close source viewer' }).click();

  await page.setViewportSize({ width: 1050, height: 900 });
  await page.locator('.result').first().click();
  const compactLayout = await page.evaluate(() => {
    const source = document.querySelector('.source-sheet').getBoundingClientRect();
    const sidebar = document.querySelector('.sidebar').getBoundingClientRect();
    return { sourceLeft: source.left, sourceRight: source.right, sidebarRight: sidebar.right };
  });
  assert.equal(compactLayout.sourceLeft, compactLayout.sidebarRight, 'compact preview should preserve the workspace sidebar');
  assert.equal(compactLayout.sourceRight, 1050);
  await page.getByRole('button', { name: 'Close source viewer' }).click();
  await page.setViewportSize({ width: 1600, height: 900 });

  await page.fill('#path-contains', '');
  await page.waitForFunction(() => document.querySelectorAll('.result-title').length === 2);
  const dateResponse = page.waitForResponse((response) => {
    const url = new URL(response.url());
    return url.pathname === '/api/search' &&
      url.searchParams.get('from')?.startsWith('2026-03-01') &&
      url.searchParams.get('until')?.startsWith('2026-03-01');
  });
  await page.fill('#imported-from', '2026-03-01');
  await page.fill('#imported-until', '2026-03-01');
  await page.focus('#query');
  assert.equal((await dateResponse).status(), 200);
  await page.waitForFunction(() => document.querySelectorAll('.result-title').length === 2 && !document.body.textContent.includes('Searching…'));
  assert.deepEqual((await page.locator('.result-title').allTextContents()).sort(), ['receipt.txt', 'report.txt']);
  await page.fill('#imported-from', '2026-03-02');
  await page.fill('#imported-until', '2026-03-02');
  await page.focus('#query');
  await page.waitForSelector('text=No results found.');
  await page.fill('#query', '7');
  await page.waitForFunction(() => document.querySelector('.result-title')?.textContent === 'number.txt');

  // A mode change must rerun the query against the same actual date-filtered archive.
  await page.selectOption('#mode', 'SEMANTIC');
  await page.waitForFunction(() => document.querySelector('.results')?.textContent.includes('Matched by semantic'));
  await page.selectOption('#mode', 'HYBRID');
  await page.waitForFunction(() => {
    const results = document.querySelector('.results')?.textContent ?? '';
    return results.includes('keyword') && results.includes('semantic');
  });
  // Every advanced control must apply without resubmitting the query.
  await page.selectOption('#media-type', 'application/pdf');
  await page.waitForSelector('text=No results found.');
  await page.selectOption('#media-type', '');
  await page.waitForFunction(() => document.querySelector('.result-title')?.textContent === 'number.txt');
  await page.selectOption('#status-filter', 'FAILED');
  await page.waitForSelector('text=No results found.');
  await page.selectOption('#status-filter', 'COMPLETE');
  await page.waitForFunction(() => document.querySelector('.result-title')?.textContent === 'number.txt');
  await page.fill('#text-contains', 'missing-author');
  await page.waitForSelector('text=No results found.');
  await page.fill('#text-contains', '');
  await page.waitForFunction(() => document.querySelector('.result-title')?.textContent === 'number.txt');
  await page.getByLabel('OCR only').check();
  await page.waitForSelector('text=No results found.');
  await page.getByLabel('OCR only').uncheck();
  await page.waitForFunction(() => document.querySelector('.result-title')?.textContent === 'number.txt');
  assert.deepEqual(errors, []);
  console.log('Search browser acceptance passed: live filters, stale response, inert highlights, date boundaries, short text and modes.');
} finally {
  await browser.close();
}
