import { chromium } from 'playwright';
import { mkdir, appendFile } from 'node:fs/promises';

const BASE_URL = process.env.BASE_URL;
const SCENARIO = process.env.SCENARIO;
const DOCUMENT_ID = process.env.DOCUMENT_ID;
const CITATION_REVISION_ID = process.env.CITATION_REVISION_ID;
const INITIAL_REVISION_ID = process.env.INITIAL_REVISION_ID;
const UNSUPPORTED_DOCUMENT_ID = process.env.UNSUPPORTED_DOCUMENT_ID;
const SCREENSHOT_DIR = process.env.SCREENSHOT_DIR;
if (!BASE_URL || !SCENARIO || !DOCUMENT_ID) throw new Error('BASE_URL, SCENARIO and DOCUMENT_ID are required');

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

async function enterAdmin(page) {
  await page.waitForSelector('#tab-admin', { timeout: 20_000 });
  await page.click('#tab-admin');
  await page.click('#admin-tab-collections');
  await page.waitForSelector('#admin-panel-collections .collections-panel', { timeout: 20_000 });
  await page.locator('#admin-panel-collections .collection-list li button', { hasText: 'Default' }).click();
  const documents = page.locator('#admin-panel-collections button[aria-controls="collection-documents"]');
  if (await documents.getAttribute('aria-expanded') !== 'true') await documents.click();
}

async function setDocumentViewState(page) {
  await page.fill('#document-search', 'three-pages');
  await page.locator('#document-search').press('Enter');
  await page.selectOption('#document-status', 'COMPLETE');
  await page.selectOption('#document-sort', 'name-asc');
  await page.locator('#admin-panel-collections table.documents tbody tr', { hasText: 'three-pages.pdf' }).waitFor({ timeout: 20_000 });
}

async function assertDocumentViewState(page, message) {
  const current = {
    query: await page.inputValue('#document-search'),
    status: await page.inputValue('#document-status'),
    sort: await page.inputValue('#document-sort'),
  };
  assert(current.query === 'three-pages' && current.status === 'COMPLETE' && current.sort === 'name-asc', `${message}: ${JSON.stringify(current)}`);
}

async function scenarioPageResources(page) {
  assert(UNSUPPORTED_DOCUMENT_ID, 'the resource scenario has a separately extracted plain-text document');
  let releaseFirstMetadata;
  let announceFirstMetadata;
  const firstMetadataHeld = new Promise((resolve) => { announceFirstMetadata = resolve; });
  const waitForFirstMetadata = new Promise((resolve) => { releaseFirstMetadata = resolve; });
  let firstMetadataRoute;
  await page.route((url) => new URL(url).pathname.endsWith('/page-review'), async (route) => {
    const url = new URL(route.request().url());
    if (url.searchParams.get('ordinal') === '1' && firstMetadataRoute === undefined) {
      firstMetadataRoute = route;
      announceFirstMetadata();
      await waitForFirstMetadata;
      await route.continue();
    } else {
      await route.continue();
    }
  });
  let releaseFirstImage;
  let announceFirstImage;
  const firstImageHeld = new Promise((resolve) => { announceFirstImage = resolve; });
  const waitForFirstImage = new Promise((resolve) => { releaseFirstImage = resolve; });
  let firstImageRoute;
  const missingImageRequests = [];
  const pageMetadataRequests = [];
  const pageImageRequests = [];
  page.on('request', (request) => {
    const pathname = new URL(request.url()).pathname;
    if (pathname.includes(`/documents/${DOCUMENT_ID}/page-review`)) {
      if (pathname.endsWith('/image')) pageImageRequests.push(request.url());
      else pageMetadataRequests.push(request.url());
    }
  });
  await page.route((url) => new URL(url).pathname.endsWith('/page-review/image'), async (route) => {
    const url = new URL(route.request().url());
    if (url.searchParams.get('ordinal') === '1' && firstImageRoute === undefined) {
      firstImageRoute = route;
      announceFirstImage();
      await waitForFirstImage;
      await route.continue();
    } else if (url.searchParams.get('ordinal') === '2') {
      missingImageRequests.push(url.toString());
      await route.fulfill({ status: 404, contentType: 'text/plain', body: 'private missing-image fixture detail' });
    } else {
      await route.continue();
    }
  });

  await enterAdmin(page);
  await setDocumentViewState(page);
  await openDocumentDetails(page);
  await page.locator('#admin-panel-collections .document-details button', { hasText: 'Open document' }).click();
  const review = page.locator('.page-review');
  await review.waitFor({ timeout: 20_000 });
  await review.getByText('Page 1 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  await review.getByRole('button', { name: 'Next page' }).click();
  await firstMetadataHeld;
  await review.getByRole('button', { name: 'Back to documents' }).click();
  await review.waitFor({ state: 'detached', timeout: 20_000 });
  releaseFirstMetadata();
  await new Promise((resolve) => setTimeout(resolve, 200));
  assert(await page.locator('.page-review').count() === 0, 'a delayed page response cannot reopen a reader after Back');
  await page.locator('#admin-panel-collections .document-details button', { hasText: 'Open document' }).click();
  await review.waitFor({ timeout: 20_000 });
  await review.getByText('Page 1 of 3', { exact: true }).waitFor({ timeout: 20_000 });

  await review.getByRole('button', { name: 'Next page' }).click();
  await review.getByText('Page 2 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  await firstImageHeld;
  const firstImageIdentity = await review.locator('.image-pane img').getAttribute('src');
  assert(firstImageIdentity && new URL(firstImageIdentity, BASE_URL).searchParams.get('ordinal') === '1',
    `the delayed request belongs to page 2: ${JSON.stringify(firstImageIdentity)}`);

  await review.getByRole('button', { name: 'Next page' }).click();
  await review.getByText('Page 3 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  const thirdPageBeforeLateImage = await review.evaluate((node) => ({
    text: node.querySelector('.review-text')?.textContent,
    image: node.querySelector('.image-pane img')?.getAttribute('src'),
  }));
  assert(thirdPageBeforeLateImage.text?.includes('Initial scan text'), `page 3 text loaded while page 2 image was delayed: ${JSON.stringify(thirdPageBeforeLateImage)}`);
  assert(missingImageRequests.length === 1 && new URL(missingImageRequests[0]).searchParams.get('ordinal') === '2',
    `page 3 requested its own image before the delayed response: ${JSON.stringify({ page: thirdPageBeforeLateImage, requests: missingImageRequests })}`);
  releaseFirstImage();
  await new Promise((resolve) => setTimeout(resolve, 200));
  const thirdPageAfterLateImage = await review.evaluate((node) => ({
    text: node.querySelector('.review-text')?.textContent,
    image: node.querySelector('.image-pane img')?.getAttribute('src'),
  }));
  assert(thirdPageAfterLateImage.text?.includes('Initial scan text') && thirdPageAfterLateImage.image == null &&
    missingImageRequests.length === 1 && new URL(missingImageRequests[0]).searchParams.get('ordinal') === '2',
  `late page-2 image cannot replace page-3 text or its missing-image state: ${JSON.stringify({ page: thirdPageAfterLateImage, requests: missingImageRequests })}`);

  const missingAlert = await review.getByRole('alert').waitFor({ timeout: 20_000 }).then(() => review.getByRole('alert').textContent());
  assert(missingAlert?.includes('This page image is unavailable.') && !missingAlert.includes('private missing-image fixture detail'),
    `a missing page image has an independent curated error: ${JSON.stringify(missingAlert)}`);
  const missingPageText = (await review.locator('.review-text').textContent()) ?? '';
  assert(missingPageText.includes('Initial scan text'), `the missing image does not remove page 3 text: ${JSON.stringify(missingPageText)}`);
  const missingImage = review.locator('.image-pane img');
  assert(await missingImage.count() === 0 && new URL(missingImageRequests[0]).searchParams.get('ordinal') === '2', 'the failed image belongs to page 3 and is removed from the image pane');
  const metadataBeforeRetry = pageMetadataRequests.length;
  const imagesBeforeRetry = pageImageRequests.length;
  await review.getByRole('button', { name: 'Retry image' }).click();
  const retryAlert = await review.getByRole('alert').waitFor({ timeout: 20_000 }).then(() => review.getByRole('alert').textContent());
  assert(retryAlert?.includes('This page image is unavailable.'), `retry keeps the missing-image error local to page 3: ${JSON.stringify(retryAlert)}`);
  assert((await review.locator('.review-text').textContent()).includes('Initial scan text'), 'image retry leaves page 3 text intact');
  assert(pageMetadataRequests.length === metadataBeforeRetry && pageImageRequests.length === imagesBeforeRetry + 1 &&
    new URL(pageImageRequests.at(-1)).searchParams.get('ordinal') === '2',
  `Retry image repeats only page 3 image retrieval: ${JSON.stringify({ before: [metadataBeforeRetry, imagesBeforeRetry], after: [pageMetadataRequests.length, pageImageRequests.length], lastImage: pageImageRequests.at(-1) })}`);
  console.log(`PASS page-resources delayedMetadata=1 delayedImage=1 lateMetadataAfterBack=true currentPage=2 lateImagePair=${JSON.stringify(thirdPageAfterLateImage)} missingImage=2 retry=only-image error=${JSON.stringify(retryAlert)}`);

  await page.setViewportSize({ width: 390, height: 900 });
  await page.locator('.source-pane button[aria-label="Back to results"]').click();
  await page.locator('.source-pane').waitFor({ state: 'hidden', timeout: 20_000 });
  await page.fill('#document-search', 'resource-notes');
  await page.locator('#document-search').press('Enter');
  const textRow = page.locator('#admin-panel-collections table.documents tbody tr', { hasText: 'resource-notes.txt' });
  await textRow.waitFor({ timeout: 20_000 });
  const existingDetails = page.locator('#admin-panel-collections .document-details');
  const existingDetailsText = await existingDetails.count() > 0 ? await existingDetails.textContent() : '';
  if (!existingDetailsText?.includes('resource-notes.txt')) {
    await textRow.locator('button', { hasText: 'Details' }).click();
  }
  const textDetails = page.locator('#admin-panel-collections .document-details');
  await textDetails.waitFor({ timeout: 20_000 });
  const textPageRequests = [];
  page.on('request', (request) => {
    if (request.url().includes(`/documents/${UNSUPPORTED_DOCUMENT_ID}/page-review`)) textPageRequests.push(request.url());
  });
  await textDetails.getByRole('button', { name: 'Open document' }).click();
  const reopenSource = page.locator('.source-workspace .reopen-source');
  if (await reopenSource.isVisible().catch(() => false)) await reopenSource.click();
  const sourceText = page.locator('.source-pane .source-text');
  await sourceText.getByText('Plain source text remains readable without any page image.').waitFor({ timeout: 20_000 });
  assert(await page.locator('.page-review').count() === 0, 'plain-text source opens in the original text viewer, not a page reader');
  assert(await page.locator('.source-pane a', { hasText: 'Open original' }).count() === 1, 'plain-text source retains its original document link');
  assert(textPageRequests.length === 0, `plain-text viewing sends no page/image pairing request: ${JSON.stringify(textPageRequests)}`);
  await page.locator('.source-pane button[aria-label="Back to results"]').click();
  await textDetails.waitFor({ timeout: 20_000 });
  await textDetails.getByRole('button', { name: 'Scan again' }).click();
  const unsupportedError = await page.getByRole('alert').waitFor({ timeout: 20_000 }).then(() => page.getByRole('alert').textContent());
  assert(unsupportedError?.includes('text/plain has no page images to read') && !unsupportedError.includes('private missing-image fixture detail'),
    `unsupported format refusal is curated while original text stays available: ${JSON.stringify(unsupportedError)}`);
  assert(textPageRequests.length === 0, `unsupported format never attempts stale page pairing: ${JSON.stringify(textPageRequests)}`);
  console.log(`PASS unsupported-text originalReadable=true pairingRequests=${textPageRequests.length} refusal=${JSON.stringify(unsupportedError)}`);
}

async function openDocumentDetails(page) {
  const panel = page.locator('#admin-panel-collections');
  const row = panel.locator('table.documents tbody tr', { hasText: 'three-pages.pdf' });
  await row.waitFor({ timeout: 30_000 });
  if (await panel.locator('.document-details').count() === 0) {
    await row.locator('button', { hasText: 'Details' }).click();
    await panel.locator('.document-details').waitFor({ timeout: 20_000 });
  }
}

async function scenarioPageBrowser(page) {
  await enterAdmin(page);
  await setDocumentViewState(page);
  await openDocumentDetails(page);
  await page.locator('#admin-panel-collections .document-details button', { hasText: 'Open document' }).click();
  const review = page.locator('.page-review');
  await review.waitFor({ timeout: 20_000 });
  await review.getByText('Page 1 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  assert(await review.getByRole('button', { name: 'Previous page' }).isDisabled(), 'Previous is disabled at page one');
  await review.getByRole('button', { name: 'Next page' }).click();
  await review.getByText('Page 2 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  await review.getByRole('button', { name: 'Previous page' }).click();
  await review.getByText('Page 1 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  await review.getByRole('button', { name: 'Next page' }).click();
  await review.getByRole('button', { name: 'Next page' }).click();
  await review.getByText('Page 3 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  assert(await review.getByRole('button', { name: 'Next page' }).isDisabled(), 'Next is disabled at the final page');
  await review.getByRole('button', { name: 'Previous page' }).click();
  await review.getByRole('button', { name: 'Previous page' }).click();
  await review.getByText('Page 1 of 3', { exact: true }).waitFor({ timeout: 20_000 });

  const widths = [390, 1024, 1440, 1920];
  const geometries = [];
  for (const width of widths) {
    await page.setViewportSize({ width, height: 900 });
    await page.waitForTimeout(100);
    const geometry = await page.evaluate(() => {
      const review = document.querySelector('.page-review');
      const comparison = review?.querySelector('.comparison');
      const imagePane = review?.querySelector('.image-pane');
      const textPane = review?.querySelector('.text-pane');
      const text = review?.querySelector('.review-text');
      const divider = review?.querySelector('[role="separator"]');
      const bounds = (node) => {
        if (!node) return null;
        const rect = node.getBoundingClientRect();
        return { x: rect.x, y: rect.y, width: rect.width, height: rect.height };
      };
      return {
        viewport: innerWidth,
        pageWidth: document.documentElement.scrollWidth,
        layout: document.querySelector('.source-workspace')?.getAttribute('data-layout'),
        review: bounds(review),
        comparison: bounds(comparison),
        imagePane: bounds(imagePane),
        textPane: bounds(textPane),
        textScroll: text ? { client: text.clientHeight, content: text.scrollHeight } : null,
        divider: divider ? { value: divider.getAttribute('aria-valuenow'), bounds: bounds(divider) } : null,
      };
    });
    assert(geometry.pageWidth <= width, `page-wide overflow at ${width}px: ${JSON.stringify(geometry)}`);
    assert(geometry.review?.width > 0 && geometry.comparison?.height > 0, `review has no usable geometry at ${width}px: ${JSON.stringify(geometry)}`);
    geometries.push(geometry);
    if (SCREENSHOT_DIR && (width === 390 || width === 1920)) {
      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await page.screenshot({ path: `${SCREENSHOT_DIR}/review-${width}.png`, fullPage: true });
    }
  }
  if (SCREENSHOT_DIR) await appendFile(`${SCREENSHOT_DIR}/geometry.jsonl`, `${JSON.stringify(geometries)}\n`);

  await page.setViewportSize({ width: 1440, height: 900 });
  const textPane = review.locator('.text-pane');
  const textRegion = textPane.locator('.review-text');
  const textScroll = await textRegion.evaluate((node) => ({ client: node.clientHeight, content: node.scrollHeight }));
  assert(textScroll.content > textScroll.client, `long extracted text must scroll inside its own pane: ${JSON.stringify(textScroll)}`);
  await textRegion.evaluate((node) => { node.scrollTop = node.scrollHeight; });
  assert(await textRegion.evaluate((node) => node.scrollTop > 0), 'text pane scrolls independently');
  const imageRegion = review.locator('.image-scroll');
  await review.getByRole('button', { name: 'Zoom in' }).click();
  await review.getByRole('button', { name: 'Zoom in' }).click();
  const imageScroll = await imageRegion.evaluate((node) => ({ client: node.clientHeight, content: node.scrollHeight }));
  assert(imageScroll.content > imageScroll.client, `zoomed image must scroll inside its own pane: ${JSON.stringify(imageScroll)}`);
  await imageRegion.evaluate((node) => { node.scrollTop = node.scrollHeight; });
  assert(await imageRegion.evaluate((node) => node.scrollTop > 0), 'image pane scrolls independently');

  const divider = review.getByRole('separator', { name: 'Resize page panes' });
  const beforeKey = Number(await divider.getAttribute('aria-valuenow'));
  await review.getByRole('button', { name: 'Scan this page again' }).focus();
  for (let step = 0; step < 8; step += 1) {
    if (await divider.evaluate((node) => node === document.activeElement)) break;
    await page.keyboard.press('Tab');
  }
  const focused = await divider.evaluate((node) => {
    const rect = node.getBoundingClientRect();
    const style = getComputedStyle(node);
    const sampleX = Math.max(0, Math.min(innerWidth - 1, rect.left + rect.width / 2));
    const visibleTop = Math.max(0, rect.top);
    const visibleBottom = Math.min(innerHeight, rect.bottom);
    const sampleY = visibleBottom > visibleTop ? visibleTop + (visibleBottom - visibleTop) / 2 : 0;
    const hit = document.elementFromPoint(sampleX, sampleY);
    return {
      active: node === document.activeElement,
      activeElement: document.activeElement?.outerHTML?.slice(0, 180),
      visibleWidth: Math.max(0, Math.min(rect.right, innerWidth) - Math.max(rect.left, 0)),
      visibleHeight: Math.max(0, Math.min(rect.bottom, innerHeight) - Math.max(rect.top, 0)),
      visiblePointHitsDivider: hit === node || node.contains(hit),
      outline: style.outlineStyle !== 'none' && style.outlineWidth !== '0px',
      boxShadow: style.boxShadow !== 'none',
    };
  });
  if (SCREENSHOT_DIR) await page.screenshot({ path: `${SCREENSHOT_DIR}/review-1440-focused.png`, fullPage: true });
  assert(focused.active && focused.visibleWidth > 0 && focused.visibleHeight > 0, `keyboard focus reaches a visible divider: ${JSON.stringify(focused)}`);
  assert(focused.visiblePointHitsDivider, `the visible divider area is not covered: ${JSON.stringify(focused)}`);
  assert(focused.outline || focused.boxShadow, `focused divider has a visible focus indicator: ${JSON.stringify(focused)}`);
  await page.keyboard.press('ArrowRight');
  await page.waitForFunction((before) => {
    const current = document.querySelector('[role="separator"][aria-label="Resize page panes"]');
    return current !== null && Number(current.getAttribute('aria-valuenow')) > before;
  }, beforeKey, { timeout: 5_000 });
  const afterKey = Number(await divider.getAttribute('aria-valuenow'));
  assert(afterKey > beforeKey, `ArrowRight changes the split (${beforeKey} → ${afterKey})`);
  const bounds = await divider.boundingBox();
  assert(bounds, 'wide divider is visible for pointer input');
  await page.mouse.move(bounds.x + bounds.width / 2, bounds.y + bounds.height / 2);
  await page.mouse.down();
  await page.mouse.move(bounds.x + bounds.width / 2 + 100, bounds.y + bounds.height / 2);
  await page.mouse.up();
  const afterPointer = Number(await divider.getAttribute('aria-valuenow'));
  assert(afterPointer !== afterKey, `pointer drag changes the split (${afterKey} → ${afterPointer})`);

  const views = ['#tab-search', '#tab-ask', '#tab-investigate', '#tab-admin'];
  const viewMeasurements = [];
  for (const width of widths) {
    await page.setViewportSize({ width, height: 900 });
    for (const tab of views) {
      await page.click(tab);
      const measured = await page.evaluate(() => ({
        width: innerWidth,
        pageWidth: document.documentElement.scrollWidth,
        active: document.querySelector('[role="tab"][aria-selected="true"]')?.id ?? null,
        main: (() => {
          const node = document.querySelector('main');
          const rect = node?.getBoundingClientRect();
          return rect ? { x: rect.x, width: rect.width } : null;
        })(),
      }));
      assert(measured.pageWidth <= width, `page-wide overflow in ${tab} at ${width}px: ${JSON.stringify(measured)}`);
      assert(measured.main?.width > 0, `main workspace has no width in ${tab} at ${width}px`);
      viewMeasurements.push(measured);
    }
  }
  if (SCREENSHOT_DIR) await appendFile(`${SCREENSHOT_DIR}/views.jsonl`, `${JSON.stringify(viewMeasurements)}\n`);

  await openDocumentDetails(page);
  if (await review.count() === 0) {
    await page.locator('#admin-panel-collections .document-details button', { hasText: 'Open document' }).click();
    await review.waitFor({ timeout: 20_000 });
  }
  await page.setViewportSize({ width: 390, height: 900 });
  await review.getByRole('button', { name: 'Next page' }).click();
  await review.getByText('Page 2 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  const textTab = review.getByRole('tab', { name: 'Text' });
  await textTab.click();
  assert(await textPane.isVisible(), 'narrow Text tab shows the text pane');
  assert(await review.locator('[role="separator"]').count() === 0, 'narrow tab layout hides the desktop divider');
  await review.getByRole('button', { name: 'Mark page 2' }).click();
  if (SCREENSHOT_DIR) await page.screenshot({ path: `${SCREENSHOT_DIR}/review-390-text.png`, fullPage: true });
  await page.locator('.source-pane button[aria-label="Back to results"]').click();
  await page.getByRole('button', { name: 'Open selected source' }).waitFor({ timeout: 20_000 });
  assert(await page.locator('.source-pane').isHidden(), 'narrow Back exposes the collection context');
  await assertDocumentViewState(page, 'Back preserves the filtered collection query and sort');
  await page.locator('#admin-panel-collections .document-details').waitFor({ timeout: 20_000 });
  await page.getByRole('button', { name: 'Open selected source' }).click();
  await review.getByText('Page 2 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  assert(await review.getByRole('button', { name: 'Mark page 2' }).getAttribute('aria-pressed') === 'false', 'reopening clears page marks while retaining the reviewed page');
}

async function scenarioAskOldCitation(page) {
  const pageRequests = [];
  page.on('request', (request) => {
    if (request.url().includes('/page-review') || request.url().includes('/sources/')) pageRequests.push(request.url());
  });
  await page.waitForSelector('#tab-ask', { timeout: 20_000 });
  await page.click('#tab-ask');
  await page.waitForSelector('#question', { timeout: 20_000 });
  await page.fill('#question', 'What does the archive say on the first page?');
  await page.waitForFunction(() => !document.querySelector('#panel-ask button[type="submit"]')?.disabled, null, { timeout: 20_000 });
  await page.locator('#panel-ask button[type="submit"]').click();
  const citation = page.locator('#panel-ask button.citation');
  await citation.waitFor({ timeout: 30_000 });
  assert((await page.locator('[aria-label="Answer"]').textContent()).includes('page one'), 'Ask shows its cited answer');
  const answerLayouts = [];
  for (const width of [390, 1024, 1440, 1920]) {
    await page.setViewportSize({ width, height: 900 });
    const layout = await page.evaluate(() => {
      const answer = document.querySelector('#panel-ask .answer');
      const history = document.querySelector('nav[aria-label="Ask history"]');
      const box = answer?.getBoundingClientRect();
      return {
        width: innerWidth,
        pageWidth: document.documentElement.scrollWidth,
        answerWidth: box?.width ?? 0,
        answerOverflow: Boolean(answer && answer.scrollWidth > answer.clientWidth + 1),
        historyVisible: Boolean(history?.closest('aside.sidebar') && history.getBoundingClientRect().width > 0),
        historyClosed: Boolean(history?.querySelector('details:not([open])')),
        mainHistoryCount: document.querySelector('.content-layout').querySelectorAll('.history-sidebar').length,
      };
    });
    assert(layout.pageWidth <= width, `Ask answer layout overflows at ${width}px: ${JSON.stringify(layout)}`);
    assert(layout.answerWidth > 0 && !layout.answerOverflow, `Ask answer remains readable at ${width}px: ${JSON.stringify(layout)}`);
    assert(layout.historyVisible && layout.historyClosed && layout.mainHistoryCount === 0, `Ask history is closed in the sidebar at ${width}px: ${JSON.stringify(layout)}`);
    if (SCREENSHOT_DIR && (width === 390 || width === 1920)) {
      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await page.screenshot({ path: `${SCREENSHOT_DIR}/ask-history-${width}.png`, fullPage: true });
    }
    answerLayouts.push(layout);
  }
  console.log(`PASS ask-layout ${JSON.stringify(answerLayouts)}`);
  console.log('MARK: ask-finished');
  assert(CITATION_REVISION_ID, 'the test must name the reading the answer cited');
  await page.waitForFunction(async ({ documentId, citationRevisionId }) => {
    const response = await fetch(`/api/collections/default/documents/${documentId}/ocr/revisions`);
    if (!response.ok) return false;
    const body = await response.json();
    return body.revisions.some((revision) => revision.active && revision.revisionId !== citationRevisionId);
  }, { documentId: DOCUMENT_ID, citationRevisionId: CITATION_REVISION_ID }, { timeout: 20_000 });
  await citation.click();
  const review = page.locator('.page-review');
  const source = page.locator('.source-pane');
  await Promise.race([
    review.waitFor({ timeout: 10_000 }).catch(() => null),
    source.waitFor({ timeout: 10_000 }).catch(() => null),
  ]);
  const stored = await page.evaluate(async () => {
    const response = await fetch('/api/collections/default/asks');
    return response.ok ? response.json() : { status: response.status };
  });
  const evidence = stored.asks?.[0]?.evidence?.find((item) => item.id === 'S1');
  assert(evidence?.revisionId === CITATION_REVISION_ID, `the saved citation names its superseded revision: ${JSON.stringify(evidence)}`);
  if (evidence.locator.type === 'pdf_page') {
    await review.getByText(`Page ${evidence.locator.page} of 3`, { exact: true }).waitFor({ timeout: 20_000 });
    assert((await review.locator('.review-text').textContent()).includes('first page records'), 'PDF citation opens its recorded page reading');
    assert(await review.getByRole('button', { name: /Mark page|Scan this page again/ }).count() === 0, 'historical reading has no scan action');
    const metadataRequest = pageRequests.find((url) => url.includes('/page-review?'));
    const imageRequest = pageRequests.find((url) => url.includes('/page-review/image?'));
    assert(metadataRequest && new URL(metadataRequest).searchParams.get('revision') === CITATION_REVISION_ID, `historical metadata stays pinned to the cited revision: ${JSON.stringify(pageRequests)}`);
    assert(imageRequest && new URL(imageRequest).searchParams.get('revision') === CITATION_REVISION_ID, `historical image stays paired to that same cited revision: ${JSON.stringify(pageRequests)}`);
    console.log(`PASS ask-history locator=${evidence.locator.type} revision=${evidence.revisionId} requests=${JSON.stringify(pageRequests)}`);
  } else {
    assert(await source.count() > 0, `non-page citation opens its recorded source, got ${JSON.stringify(pageRequests)}`);
    assert((await source.locator('.source-text').textContent()).includes('first page records'), 'text-line citation reads the matching page text');
    const sourceRequest = pageRequests.find((url) => url.includes('/sources/'));
    assert(sourceRequest && new URL(sourceRequest).searchParams.get('revision') === CITATION_REVISION_ID, `historical source stays pinned to the cited revision: ${JSON.stringify(pageRequests)}`);
    console.log(`PASS ask-history locator=${evidence.locator.type} revision=${evidence.revisionId}`);
  }
}

async function scenarioAskPromptFromAdmin(page) {
  await enterAdmin(page);
  const settings = page.locator('details.secondary-settings:has(section[aria-label="Settings for Default"])');
  if (!(await settings.evaluate((node) => node.open))) await settings.locator(':scope > summary').click();
  const promptSection = page.locator('section.prompt-settings[aria-label="Prompts for Default"]');
  await promptSection.waitFor({ timeout: 20_000 });
  await promptSection.locator('section[data-role="ASK"]').getByRole('button', { name: 'New prompt' }).click();
  await promptSection.locator('#collection-prompt-name').fill('Short');
  await promptSection.locator('#collection-prompt-body').fill('Answer in one line.');
  await promptSection.getByRole('button', { name: 'Save prompt' }).click();
  await promptSection.getByText('Prompt saved.', { exact: true }).waitFor({ timeout: 20_000 });
  console.log('PASS prompt-created ask role=Short');

  await page.waitForSelector('#tab-ask', { timeout: 20_000 });
  await page.click('#tab-ask');
  await page.waitForSelector('#question', { timeout: 20_000 });
  await page.waitForFunction(() => [...document.querySelectorAll('#prompt-ask option')].some((option) => option.textContent === 'Short'), null, { timeout: 20_000 });
  await page.locator('#prompt-ask').selectOption({ label: 'Short' });
  await page.fill('#question', 'What is on the first page?');
  await page.waitForFunction(() => !document.querySelector('#panel-ask button[type="submit"]')?.disabled, null, { timeout: 20_000 });
  await page.locator('#panel-ask button[type="submit"]').click();
  await page.waitForFunction(() => document.querySelector('[aria-label="Answer"]')?.textContent.includes('Short answer'), null, { timeout: 30_000 });
  console.log('PASS ask-with-prompt answer=Short');
}

async function scenarioAskImageOldCitation(page) {
  const pageRequests = [];
  page.on('request', (request) => {
    if (request.url().includes('/page-review') || request.url().includes('/sources/')) pageRequests.push(request.url());
  });
  await page.click('#tab-ask');
  await page.waitForSelector('#question', { timeout: 20_000 });
  await page.fill('#question', 'What phrase is stamped on the cobalt lantern?');
  await page.waitForFunction(() => !document.querySelector('#panel-ask button[type="submit"]')?.disabled, null, { timeout: 20_000 });
  await page.locator('#panel-ask button[type="submit"]').click();
  const citation = page.locator('#panel-ask button.citation');
  await citation.waitFor({ timeout: 30_000 });
  assert((await page.locator('[aria-label="Answer"]').textContent()).includes('cobalt lantern 419'), 'Ask shows its image citation answer');
  console.log('MARK: ask-finished');
  assert(CITATION_REVISION_ID, 'the test must name the reading the answer cited');
  await page.waitForFunction(async ({ documentId, citationRevisionId }) => {
    const response = await fetch(`/api/collections/default/documents/${documentId}/ocr/revisions`);
    if (!response.ok) return false;
    const body = await response.json();
    return body.revisions.some((revision) => revision.active && revision.revisionId !== citationRevisionId);
  }, { documentId: DOCUMENT_ID, citationRevisionId: CITATION_REVISION_ID }, { timeout: 20_000 });
  const stored = await page.evaluate(async () => {
    const response = await fetch('/api/collections/default/asks');
    return response.ok ? response.json() : { status: response.status };
  });
  const evidence = stored.asks?.[0]?.evidence?.find((item) => item.id === 'S1');
  assert(evidence?.revisionId === CITATION_REVISION_ID, `the saved image citation names its superseded revision: ${JSON.stringify(evidence)}`);
  assert(evidence.locator.type === 'image', `the fixture produces an image locator: ${JSON.stringify(evidence.locator)}`);
  await citation.click();
  const review = page.locator('.page-review');
  const source = page.locator('.source-pane');
  await Promise.race([
    review.waitFor({ timeout: 10_000 }).catch(() => null),
    source.waitFor({ state: 'visible', timeout: 10_000 }).catch(() => null),
  ]);
  assert(await review.isVisible(), `a known-revision image citation must open page review, sourceVisible=${await source.isVisible()}, requests=${JSON.stringify(pageRequests)}`);
  await review.getByText('Page 1 of 1', { exact: true }).waitFor({ timeout: 20_000 });
  assert((await review.locator('.review-text').textContent()).includes('Cobalt Lantern 419'), 'the historical page text is shown');
  assert(await review.getByRole('button', { name: 'Refresh to latest reading' }).count() === 0, 'historical image citation has no live refresh action');
  const metadataRequest = pageRequests.find((url) => url.includes('/page-review?'));
  const imageRequest = pageRequests.find((url) => url.includes('/page-review/image?'));
  assert(metadataRequest && new URL(metadataRequest).searchParams.get('revision') === CITATION_REVISION_ID, `image citation metadata stays pinned to its revision: ${JSON.stringify(pageRequests)}`);
  assert(imageRequest && new URL(imageRequest).searchParams.get('revision') === CITATION_REVISION_ID, `image citation image stays paired to its revision: ${JSON.stringify(pageRequests)}`);
  assert(new URL(imageRequest).searchParams.get('ordinal') === '0' && new URL(imageRequest).searchParams.get('kind') === 'original', `single-image citation resolves to its exact original image: ${JSON.stringify(pageRequests)}`);
  console.log(`PASS ask-history locator=${evidence.locator.type} revision=${evidence.revisionId} requests=${JSON.stringify(pageRequests)}`);
}

async function scenarioAskImageCreate(page) {
  await page.click('#tab-ask');
  await page.waitForSelector('#question', { timeout: 20_000 });
  await page.fill('#question', 'What phrase is stamped on the cobalt lantern?');
  await page.waitForFunction(() => !document.querySelector('#panel-ask button[type="submit"]')?.disabled, null, { timeout: 20_000 });
  await page.locator('#panel-ask button[type="submit"]').click();
  const citation = page.locator('#panel-ask button.citation');
  await citation.waitFor({ timeout: 30_000 });
  assert(CITATION_REVISION_ID, 'the test must name the reading being retired');
  await page.waitForFunction(async ({ citationRevisionId }) => {
    const response = await fetch('/api/collections/default/asks', { cache: 'no-store' });
    if (!response.ok) return false;
    const body = await response.json();
    return body.asks?.some((ask) => ask.title === 'Image excerpt' &&
      ask.question.toLowerCase().includes('cobalt lantern') &&
      ask.evidence?.some((item) => item.id === 'S1' && item.revisionId === citationRevisionId));
  }, { citationRevisionId: CITATION_REVISION_ID }, { timeout: 20_000 });
  const stored = await page.evaluate(async () => {
    const response = await fetch('/api/collections/default/asks', { cache: 'no-store' });
    return response.ok ? response.json() : { status: response.status };
  });
  const evidence = stored.asks?.find((ask) => ask.question.toLowerCase().includes('cobalt lantern'))?.evidence?.find((item) => item.id === 'S1');
  assert(evidence?.revisionId === CITATION_REVISION_ID && evidence.locator.type === 'image', `the generated Ask stores its known image citation before the fixture changes it: ${JSON.stringify(evidence)}`);
  console.log(`PASS ask-generated locator=${evidence.locator.type} revision=${evidence.revisionId} title=Image excerpt`);
}

async function scenarioAskImageUnknownCitation(page) {
  const pageRequests = [];
  page.on('request', (request) => {
    if (request.url().includes('/page-review') || request.url().includes('/sources/')) pageRequests.push(request.url());
  });
  const askResponseDiagnostics = [];
  page.on('response', (response) => {
    if (response.url().includes('/api/collections/default/asks')) {
      askResponseDiagnostics.push(Promise.all([
        response.allHeaders(), response.fromServiceWorker(), response.request().allHeaders(),
      ]).then(([headers, fromServiceWorker, requestHeaders]) => ({ headers, fromServiceWorker, requestHeaders })));
    }
  });
  await page.evaluate(() => {
    window.__askHistoryFetches = [];
    const originalFetch = window.fetch.bind(window);
    window.fetch = async (input, init) => {
      const response = await originalFetch(input, init);
      const url = typeof input === 'string' ? input : input.url;
      if (url.includes('/api/collections/default/asks')) {
        const body = await response.clone().json().catch(() => null);
        const resource = performance.getEntriesByName(url).at(-1);
        window.__askHistoryFetches.push({
          cache: init?.cache ?? (input instanceof Request ? input.cache : null),
          headers: Object.fromEntries(response.headers.entries()),
          transferSize: resource?.transferSize ?? null,
          body,
        });
      }
      return response;
    };
  });
  await page.click('#tab-ask');
  const history = page.locator('nav[aria-label="Ask history"]');
  assert(await history.locator('details').getAttribute('open') === null, 'Ask history starts closed');
  await history.locator('summary').click();
  const savedAnswer = history.getByRole('button', { name: 'Image excerpt', exact: true });
  await savedAnswer.waitFor({ timeout: 20_000 });
  await history.getByRole('button', { name: 'New conversation' }).click();
  await savedAnswer.click();
  await page.locator('.stored-question').filter({ hasText: 'What phrase is stamped on the cobalt lantern?' }).waitFor({ timeout: 20_000 });
  const stored = await page.evaluate(async () => {
    const response = await fetch('/api/collections/default/asks', { cache: 'no-store' });
    return response.ok ? response.json() : { status: response.status };
  });
  const targetAsk = stored.asks?.find((ask) => ask.question.toLowerCase().includes('cobalt lantern'));
  const evidence = targetAsk?.evidence?.find((item) => item.id === 'S1' && item.revisionId == null);
  const askHistoryFetches = await page.evaluate(() => window.__askHistoryFetches);
  const askNetworkResponses = await Promise.all(askResponseDiagnostics);
  const askWireEvidence = askHistoryFetches.map(({ cache, headers, transferSize, body }) => ({
    cache,
    headers,
    transferSize,
    revisionIds: body?.asks?.find((ask) => ask.question.toLowerCase().includes('cobalt lantern'))?.evidence
      ?.filter((item) => item.id === 'S1').map((item) => item.revisionId) ?? [],
  }));
  assert(evidence && evidence.revisionId == null, `the saved excerpt has no known reading revision: ${JSON.stringify(evidence)}`);
  assert(evidence.locator.type === 'image', `the unknown-revision fixture keeps its image locator: ${JSON.stringify(evidence.locator)}`);
  assert(askWireEvidence[0]?.revisionIds.length > 0 && askWireEvidence[0].revisionIds.every((revisionId) => revisionId == null),
    `the UI's ordinary Ask-history GET sees the post-mutation unknown revision: ${JSON.stringify({ askWireEvidence, askNetworkResponses })}`);
  const savedCitation = page.locator('#panel-ask button.citation');
  await savedCitation.waitFor({ timeout: 20_000 });
  await savedCitation.click();
  const source = page.locator('.source-pane');
  await source.waitFor({ state: 'visible', timeout: 20_000 });
  const excerptView = await source.textContent();
  assert(excerptView?.includes('Revision unknown.'), `unknown revision is labelled; source=${excerptView}; pageReview=${await page.locator('.page-review').count()}; requests=${JSON.stringify(pageRequests)}; askHistoryFetches=${JSON.stringify(askHistoryFetches)}; askNetworkResponses=${JSON.stringify(askNetworkResponses)}`);
  assert((await source.locator('.source-text').textContent()).includes(evidence.excerpt.slice(0, 40)), 'the saved excerpt is shown verbatim');
  assert(await page.locator('.page-review').count() === 0, 'an unknown-revision excerpt does not mount page review');
  assert(pageRequests.length === 0, `unknown-revision excerpt sends no source or image pairing requests: ${JSON.stringify(pageRequests)}`);
  console.log(`PASS ask-unknown-excerpt locator=${evidence.locator.type} pairingRequests=${pageRequests.length} uiAskWire=${JSON.stringify(askWireEvidence)} network=${JSON.stringify(askNetworkResponses)}`);
}

async function scenarioSelectablePdf(page) {
  await enterAdmin(page);
  await openDocumentDetails(page);
  await page.locator('#admin-panel-collections .document-details button', { hasText: 'Open document' }).click();
  const review = page.locator('.page-review');
  await review.waitFor({ timeout: 20_000 });
  await review.getByText('Page 1 of 3', { exact: true }).waitFor({ timeout: 20_000 });

  const actions = review.locator('[role="group"][aria-label="Selectable PDF copy"]');
  await actions.waitFor({ timeout: 20_000 });
  // A document's plain first import does not go through RevisionPublicationService.publish() (it commits
  // through StoredUnitsSink directly, same as every other first import in this codebase), so the
  // publication hook that auto-queues selectable-PDF generation never fires here — only an explicit
  // rescan or restore publication does. This is the documented, still-open "Create PDF with selectable
  // text" path (spec: "Existing archived PDFs are eligible through an explicit Create PDF with selectable
  // text action"), so this scenario starts generation explicitly through that real UI control.
  const pageMetadataResponse = await page.request.get(`${BASE_URL}/api/collections/default/documents/${DOCUMENT_ID}/page-review?ordinal=0`);
  assert(pageMetadataResponse.ok(), `baseline page metadata is readable: ${pageMetadataResponse.status()}`);
  const revisionId = (await pageMetadataResponse.json()).revisionId;
  assert(revisionId, 'the document has a published revision to generate a selectable PDF for');

  await actions.getByText('No PDF with selectable text has been made for this revision.').waitFor({ timeout: 20_000 });
  await actions.getByRole('button', { name: 'Create PDF with selectable text' }).click();

  const statusUrl = `${BASE_URL}/api/collections/default/documents/${DOCUMENT_ID}/selectable-pdf?revision=${encodeURIComponent(revisionId)}`;
  let finalStatus;
  const deadline = Date.now() + 60_000;
  while (Date.now() < deadline) {
    const response = await page.request.get(statusUrl);
    assert(response.ok(), `selectable-PDF status is readable: HTTP ${response.status()} ${await response.text()}`);
    const body = await response.json();
    if (body.state === 'READY' || body.state === 'PARTIAL' || body.state === 'FAILED') { finalStatus = body; break; }
    await new Promise((resolve) => setTimeout(resolve, 200));
  }
  assert(finalStatus, `selectable-PDF generation reached a terminal state within 60 seconds`);
  assert(finalStatus.state === 'READY', `expected a fully selectable three-page export: ${JSON.stringify(finalStatus)}`);
  assert(finalStatus.totalPages === 3 && finalStatus.selectablePages === 3, `expected every physical page to be selectable: ${JSON.stringify(finalStatus)}`);
  console.log(`STATUS selectable-pdf-generated ${JSON.stringify(finalStatus)}`);

  await page.waitForFunction(() => {
    const group = document.querySelector('[role="group"][aria-label="Selectable PDF copy"]');
    return Boolean(group && /Selectable text on \d+ of \d+ pages/.test(group.textContent ?? ''));
  }, null, { timeout: 20_000 });
  const statusText = (await actions.textContent())?.replace(/\s+/g, ' ').trim() ?? '';
  assert(statusText.includes('Selectable text on 3 of 3 pages'), `expected the UI to reflect the same fully selectable export: ${JSON.stringify(statusText)}`);

  const openLink = actions.locator('a', { hasText: 'Open PDF with selectable text' });
  const downloadLink = actions.locator('a', { hasText: 'Download PDF with selectable text' });
  await openLink.waitFor({ timeout: 20_000 });
  const openHref = await openLink.getAttribute('href');
  const downloadHref = await downloadLink.getAttribute('href');
  assert(openHref && downloadHref, `both open and download actions are present: ${JSON.stringify({ openHref, downloadHref })}`);
  const downloadUrl = new URL(downloadHref, BASE_URL);
  assert(downloadUrl.searchParams.get('revision') === revisionId, `the download link names its exact revision: ${downloadHref}`);

  // Download the real file through the real route, and independently verify it is a non-trivial PDF — not
  // merely that the route answered 200 or that the button exists.
  const downloaded = await page.request.get(downloadUrl.toString());
  assert(downloaded.ok(), `the real file route serves the generated PDF: HTTP ${downloaded.status()}`);
  assert((downloaded.headers()['content-type'] ?? '').includes('application/pdf'), `the response is served as a PDF: ${JSON.stringify(downloaded.headers())}`);
  const bytes = await downloaded.body();
  assert(bytes.length > 500, `the downloaded file is a non-trivial PDF, got ${bytes.length} bytes`);
  assert(bytes.subarray(0, 5).toString('latin1') === '%PDF-', `the downloaded bytes are a real PDF, not a stub: ${JSON.stringify(bytes.subarray(0, 16).toString('latin1'))}`);

  // Repeating the download and the create request must never duplicate the artifact or enqueue a second job.
  const secondDownload = await page.request.get(downloadUrl.toString());
  assert(secondDownload.ok(), 'repeating the download is still served');
  const secondBytes = await secondDownload.body();
  assert(Buffer.compare(bytes, secondBytes) === 0, 'repeated download serves byte-identical content');

  const createResponse = await page.evaluate(async ({ documentId, revisionId }) => {
    const session = await fetch('/api/session');
    const { csrfToken } = await session.json();
    const response = await fetch(`/api/collections/default/documents/${documentId}/selectable-pdf`, {
      method: 'POST',
      headers: { 'content-type': 'application/json', 'X-InfoScry-Csrf': csrfToken },
      body: JSON.stringify({ revisionId, retry: false }),
    });
    return { status: response.status, body: await response.json() };
  }, { documentId: DOCUMENT_ID, revisionId });
  assert(createResponse.status === 200 && createResponse.body.state === 'READY' && createResponse.body.selectablePages === 3,
    `a repeated POST create answers the same verified READY identity: ${JSON.stringify(createResponse)}`);
  console.log(`PASS selectable-pdf-ready status=${JSON.stringify(statusText)} bytes=${bytes.length} revision=${revisionId}`);

  // Acceptance vector 4: the controls fit every required viewport width without page-wide overflow, and
  // the existing historical-citation/source-selection behavior around them (covered by the other scenarios
  // in this file) is otherwise untouched by this feature.
  const widths = [390, 1024, 1440, 1920];
  const geometries = [];
  for (const width of widths) {
    await page.setViewportSize({ width, height: 900 });
    await page.waitForTimeout(100);
    const geometry = await page.evaluate(() => {
      const group = document.querySelector('[role="group"][aria-label="Selectable PDF copy"]');
      const rect = group?.getBoundingClientRect();
      return {
        viewport: innerWidth,
        pageWidth: document.documentElement.scrollWidth,
        groupWidth: rect?.width ?? 0,
        groupOverflow: Boolean(group && group.scrollWidth > group.clientWidth + 1),
      };
    });
    assert(geometry.pageWidth <= width, `selectable-PDF controls cause page-wide overflow at ${width}px: ${JSON.stringify(geometry)}`);
    assert(geometry.groupWidth > 0 && !geometry.groupOverflow, `selectable-PDF controls wrap without overflowing their own group at ${width}px: ${JSON.stringify(geometry)}`);
    geometries.push(geometry);
    if (SCREENSHOT_DIR && (width === 390 || width === 1920)) {
      await mkdir(SCREENSHOT_DIR, { recursive: true });
      await page.screenshot({ path: `${SCREENSHOT_DIR}/selectable-pdf-${width}.png`, fullPage: true });
    }
  }
  if (SCREENSHOT_DIR) await appendFile(`${SCREENSHOT_DIR}/selectable-pdf-geometry.jsonl`, `${JSON.stringify(geometries)}\n`);

  await page.setViewportSize({ width: 1440, height: 900 });
  await review.getByRole('button', { name: 'Previous page' }).focus();
  let reached = false;
  for (let step = 0; step < 20; step += 1) {
    await page.keyboard.press('Tab');
    const active = await page.evaluate(() => ({
      text: document.activeElement?.textContent?.trim() ?? null,
      visible: Boolean(document.activeElement && document.activeElement.getBoundingClientRect().width > 0),
    }));
    if (active.text === 'Open PDF with selectable text' && active.visible) { reached = true; break; }
  }
  assert(reached, 'keyboard focus can reach the visible "Open PDF with selectable text" link');
  console.log('PASS selectable-pdf-viewports widths=' + JSON.stringify(widths) + ' keyboardFocusReached=' + reached);
}

async function scenarioPageSelection(page) {
  await enterAdmin(page);
  await openDocumentDetails(page);
  await page.locator('#admin-panel-collections .document-details button', { hasText: 'Open document' }).click();
  const review = page.locator('.page-review');
  await review.waitFor({ timeout: 20_000 });
  await review.getByText('Page 1 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  const baseline = [];
  for (const ordinal of [0, 1, 2]) {
    const response = await page.request.get(`${BASE_URL}/api/collections/default/documents/${DOCUMENT_ID}/page-review?ordinal=${ordinal}`);
    assert(response.ok(), `baseline page ${ordinal} is readable: ${response.status()}`);
    baseline.push(await response.json());
  }
  const previewResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST' && response.url().endsWith(`/api/collections/default/documents/${DOCUMENT_ID}/ocr/preview`),
    { timeout: 20_000 },
  );
  await review.getByRole('button', { name: 'Scan this page again' }).click();
  const dialog = page.getByTestId('start-reading-dialog');
  await dialog.waitFor({ timeout: 20_000 });
  await page.waitForFunction(() => {
    const text = document.querySelector('[data-testid="start-reading-dialog"]')?.textContent ?? '';
    return !text.includes('Preparing page summary…') && !text.includes('Loading reading methods…');
  }, null, { timeout: 20_000 });
  const summary = (await dialog.textContent())?.replace(/\s+/g, ' ').trim() ?? '';
  assert(/1 page will be read.*of 3 in the document/i.test(summary), `expected a one-of-three preview, got ${JSON.stringify(summary)}`);
  assert(summary.includes('Pages to read: 1'), `the explicit confirmation names only the selected clean-text page 1, got ${JSON.stringify(summary)}`);
  const method = await dialog.locator('#reading-method').inputValue();
  assert(method.startsWith('llm:'), `the browser scenario uses its confirmed local fake LLM profile, got ${JSON.stringify(method)}`);
  const preview = await (await previewResponsePromise).json();
  assert(preview.selection?.mode === 'explicit', `the preview request keeps explicit selection: ${JSON.stringify(preview.selection)}`);
  assert(JSON.stringify(preview.selection.ordinals) === '[0]', `the preview includes only zero-based clean-text page 0: ${JSON.stringify(preview.selection)}`);
  const startResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST' && response.url().endsWith(`/api/collections/default/documents/${DOCUMENT_ID}/ocr/rescan`),
    { timeout: 20_000 },
  );
  await dialog.getByRole('button', { name: /^Read page 1 with / }).click();
  await dialog.waitFor({ state: 'detached', timeout: 20_000 });
  const started = await (await startResponse).json();
  assert(typeof started.operationId === 'string', `rescan start did not return an operation id: ${JSON.stringify(started)}`);
  await page.waitForFunction(() => {
    const text = document.querySelector('.source-pane [data-testid="document-reading-status"]')?.textContent ?? '';
    return text.includes('Reading 0 of 1');
  }, null, { timeout: 10_000 });
  await page.waitForFunction(async ({ documentId, operationId }) => {
    const response = await fetch(`/api/collections/default/documents/${documentId}/ocr/operations/${encodeURIComponent(operationId)}`);
    if (!response.ok) return false;
    const operation = await response.json();
    if (operation.stage === 'FAILED' || operation.stage === 'CANCELLED') {
      throw new Error(`rescan ended at ${operation.stage}: ${operation.errorCode} ${operation.errorMessage}`);
    }
    return operation.stage === 'COMPLETE';
  }, { documentId: DOCUMENT_ID, operationId: started.operationId }, { timeout: 90_000 });
  await page.waitForFunction(() => !!document.querySelector('.page-review button[aria-label="Refresh to latest reading"]'), null, { timeout: 30_000 });
  await review.getByRole('button', { name: 'Refresh to latest reading' }).click();
  const textTab = review.getByRole('tab', { name: 'Text' });
  if (await textTab.count() > 0) await textTab.click();
  await review.locator('.review-text').getByText('OCR result for scanned page', { exact: false }).waitFor({ timeout: 20_000 });
  const pagePair = await review.evaluate((node) => {
    const image = node.querySelector('.image-pane img');
    return {
      text: node.querySelector('.review-text')?.textContent,
      image: image?.getAttribute('src'),
      revision: image ? new URL(image.getAttribute('src'), location.href).searchParams.get('revision') : null,
      ordinal: image ? new URL(image.getAttribute('src'), location.href).searchParams.get('ordinal') : null,
    };
  });
  assert(pagePair.text?.includes('OCR result for scanned page'), `reader text shows the new exact page: ${JSON.stringify(pagePair)}`);
  assert(pagePair.revision, `reader image is bound to the published revision: ${JSON.stringify(pagePair)}`);
  assert(pagePair.ordinal === '0', `the new text/image pair is for selected page 1: ${JSON.stringify(pagePair)}`);
  const operationResponse = await page.request.get(`${BASE_URL}/api/collections/default/documents/${DOCUMENT_ID}/ocr/operations/${encodeURIComponent(started.operationId)}`);
  const completed = await operationResponse.json();
  assert(completed.selection?.mode === 'explicit', `the admitted browser run keeps explicit selection: ${JSON.stringify(completed.selection)}`);
  assert(JSON.stringify(completed.selection.ordinals) === '[0]', `only zero-based clean-text page 0 was admitted: ${JSON.stringify(completed.selection)}`);
  const metadataResponse = await page.request.get(`${BASE_URL}/api/collections/default/documents/${DOCUMENT_ID}/page-review?ordinal=0`);
  assert(metadataResponse.ok(), `published page metadata is available: ${metadataResponse.status()}`);
  const metadata = await metadataResponse.json();
  assert(metadata.text.includes('OCR result for scanned page'), 'the reread text is visible for the selected page');
  assert(metadata.readingMethod?.startsWith('LLM') && metadata.readingMethod.includes('fake-vision'), `page provenance names the selected fake profile: ${JSON.stringify(metadata.readingMethod)}`);
  const imageResponse = await page.request.get(`${BASE_URL}/api/collections/default/documents/${DOCUMENT_ID}/page-review/image?ordinal=0&revision=${encodeURIComponent(metadata.revisionId)}&kind=ocr`);
  assert(imageResponse.ok(), `the selected page image loads from the same published revision: ${imageResponse.status()}`);
  assert(INITIAL_REVISION_ID && metadata.revisionId !== INITIAL_REVISION_ID, `the selected scan published a new reading: initial=${INITIAL_REVISION_ID} current=${metadata.revisionId}`);
  const oldPageResponse = await page.request.get(`${BASE_URL}/api/collections/default/documents/${DOCUMENT_ID}/page-review?ordinal=0&revision=${encodeURIComponent(INITIAL_REVISION_ID)}`);
  assert(oldPageResponse.ok(), `the original clean page remains readable in history: ${oldPageResponse.status()}`);
  const oldPage = await oldPageResponse.json();
  assert(oldPage.text.includes('first page records'), `history retains the page's exact prior text: ${JSON.stringify(oldPage)}`);
  for (const ordinal of [1, 2]) {
    const response = await page.request.get(`${BASE_URL}/api/collections/default/documents/${DOCUMENT_ID}/page-review?ordinal=${ordinal}&revision=${encodeURIComponent(metadata.revisionId)}`);
    assert(response.ok(), `unselected page ${ordinal} remains available in the new revision: ${response.status()}`);
    const current = await response.json();
    assert(current.unitId === baseline[ordinal].unitId, `unselected page ${ordinal} keeps its stable content ID: ${JSON.stringify({ before: baseline[ordinal], after: current })}`);
    assert(current.text === baseline[ordinal].text, `unselected page ${ordinal} keeps its text: ${JSON.stringify({ before: baseline[ordinal].text, after: current.text })}`);
    assert(current.readingMethod === baseline[ordinal].readingMethod, `unselected page ${ordinal} keeps its producer provenance: ${JSON.stringify({ before: baseline[ordinal].readingMethod, after: current.readingMethod })}`);
  }
  console.log(`PASS selected-clean-page selection=${JSON.stringify(completed.selection)} progress=Reading 0 of 1 newRevision=${metadata.revisionId} oldRevision=${INITIAL_REVISION_ID}`);
}

async function scenarioPageSelectionRetry(page) {
  await enterAdmin(page);
  await setDocumentViewState(page);
  await openDocumentDetails(page);
  await page.locator('#admin-panel-collections .document-details button', { hasText: 'Open document' }).click();
  const review = page.locator('.page-review');
  await review.waitFor({ timeout: 20_000 });
  await review.getByText('Page 1 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  await review.getByRole('button', { name: 'Mark page 1' }).click();
  await review.getByRole('button', { name: 'Next page' }).click();
  await review.getByRole('button', { name: 'Next page' }).click();
  await review.getByText('Page 3 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  await review.getByRole('button', { name: 'Mark page 3' }).click();
  assert((await review.textContent()).includes('Selected pages: 1, 3'), 'the user selected exact physical pages 1 and 3');

  const previewResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST' && response.url().endsWith(`/api/collections/default/documents/${DOCUMENT_ID}/ocr/preview`),
    { timeout: 20_000 },
  );
  await review.getByRole('button', { name: 'Scan selected pages' }).click();
  const dialog = page.getByTestId('start-reading-dialog');
  await dialog.waitFor({ timeout: 20_000 });
  await page.waitForFunction(() => {
    const text = document.querySelector('[data-testid="start-reading-dialog"]')?.textContent ?? '';
    return !text.includes('Preparing page summary…') && !text.includes('Loading reading methods…');
  }, null, { timeout: 20_000 });
  const preview = await (await previewResponse).json();
  assert(preview.selection?.mode === 'explicit' && JSON.stringify(preview.selection.ordinals) === '[0,2]',
    `preview names only selected pages 1 and 3: ${JSON.stringify(preview.selection)}`);
  const startResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST' && response.url().endsWith(`/api/collections/default/documents/${DOCUMENT_ID}/ocr/rescan`),
    { timeout: 20_000 },
  );
  await dialog.getByRole('button', { name: /^Read pages 1, 3 with / }).click();
  await dialog.waitFor({ state: 'detached', timeout: 20_000 });
  const started = await (await startResponse).json();
  assert(typeof started.operationId === 'string', `rescan start did not return an operation id: ${JSON.stringify(started)}`);
  const operationUrl = `${BASE_URL}/api/collections/default/documents/${DOCUMENT_ID}/ocr/operations/${encodeURIComponent(started.operationId)}`;
  let failedAttempt;
  const operationDeadline = Date.now() + 30_000;
  while (!failedAttempt && Date.now() < operationDeadline) {
    const response = await page.request.get(operationUrl);
    assert(response.ok(), `operation lookup failed: HTTP ${response.status()} ${await response.text()}`);
    const operation = await response.json();
    if (operation.stage === 'FAILED' || operation.stage === 'COMPLETE') failedAttempt = operation;
    else await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert(failedAttempt, 'the first selected-page attempt reaches a terminal state within 30 seconds');
  assert(failedAttempt.stage === 'FAILED', `the first attempt fails after a committed page checkpoint: ${JSON.stringify(failedAttempt)}`);
  assert(failedAttempt.selection?.mode === 'explicit' && JSON.stringify(failedAttempt.selection.ordinals) === '[0,2]',
    `failed attempt keeps the explicit selection: ${JSON.stringify(failedAttempt)}`);
  assert(failedAttempt.pageTotal === 2 && failedAttempt.pagesCommitted === 1,
    `failure happens after exactly one selected page checkpoint: ${JSON.stringify(failedAttempt)}`);
  assert(failedAttempt.errorMessage && !failedAttempt.errorMessage.includes('synthetic fake provider failure'),
    `the page reader shows a curated failure, not a raw provider exception: ${JSON.stringify(failedAttempt.errorMessage)}`);
  let visibleFailure = [];
  const visibleFailureDeadline = Date.now() + 10_000;
  while (Date.now() < visibleFailureDeadline) {
    visibleFailure = await page.locator('[data-testid="document-reading-status"]').allTextContents();
    if (visibleFailure.some((text) => text.includes('Failed'))) break;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  assert(visibleFailure.some((text) => text.includes('Failed')), `the stopped reading is visible with its safe failure status after polling: ${JSON.stringify(visibleFailure)}`);

  await review.getByRole('button', { name: 'Back to documents' }).click();
  await review.waitFor({ state: 'detached', timeout: 20_000 });
  const details = page.locator('#admin-panel-collections .document-details');
  await details.waitFor({ state: 'visible', timeout: 20_000 });
  await details.locator('button', { hasText: 'Open document' }).click();
  await review.waitFor({ timeout: 20_000 });
  assert(await review.getByRole('button', { name: 'Mark page 1' }).getAttribute('aria-pressed') === 'false', 'Back clears the explicit page marks before the retry');
  await review.getByRole('button', { name: 'Next page' }).click();
  await review.getByRole('button', { name: 'Next page' }).click();
  await review.getByText('Page 3 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  assert(await review.getByRole('button', { name: 'Mark page 3' }).getAttribute('aria-pressed') === 'false', 'Back clears both selected page marks before the retry');
  await review.getByRole('button', { name: 'Back to documents' }).click();
  await review.waitFor({ state: 'detached', timeout: 20_000 });
  await details.waitFor({ state: 'visible', timeout: 20_000 });

  const availableActions = await details.locator('[data-testid="document-rescan"] button').allTextContents();
  const detailStatus = await details.locator('[data-testid="document-reading-status"]').allTextContents();
  const operationsResponse = await page.request.get(`${BASE_URL}/api/collections/default/documents/${DOCUMENT_ID}/ocr/operations`);
  const currentOperations = (await operationsResponse.json()).operations ?? [];
  const operationSummary = currentOperations.map(({ operationId, stage, selection, pagesCommitted, pageTotal }) => ({ operationId, stage, selection, pagesCommitted, pageTotal }));
  console.log(`AFTER-BACK detailStatus=${JSON.stringify(detailStatus)} actions=${JSON.stringify(availableActions)} operations=${JSON.stringify(operationSummary)}`);
  const operationId = failedAttempt.operationId;
  const action = details.getByRole('button', { name: 'Resume selected pages' });
  const actionState = { text: await action.textContent(), visible: await action.isVisible(), disabled: await action.isDisabled() };
  const requests = [];
  const responses = [];
  page.on('request', (request) => {
    if (request.method() === 'POST') requests.push({ url: request.url(), body: request.postData() });
  });
  page.on('response', (response) => {
    if (response.request().method() === 'POST') responses.push({ url: response.url(), status: response.status() });
  });
  const resumeResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST' && response.url().endsWith(`/api/collections/default/documents/${DOCUMENT_ID}/ocr/operations/${encodeURIComponent(operationId)}/resume`),
    { timeout: 30_000 },
  );
  await action.click();
  const resumeResponse = await resumeResponsePromise;
  assert(resumeResponse.status() === 202, `the stopped explicit run resumes through its existing operation endpoint: HTTP ${resumeResponse.status()}`);
  const resumed = await resumeResponse.json();
  assert(resumed.operationId === operationId, `resume continues the same operation: ${JSON.stringify(resumed)}`);
  assert(resumed.selection?.mode === 'explicit' && JSON.stringify(resumed.selection.ordinals) === '[0,2]',
    `resume preserves the exact selected pages and checkpoint scope: ${JSON.stringify(resumed.selection)}`);
  console.log(`RESUME action=${JSON.stringify(actionState)} requests=${JSON.stringify(requests)} responses=${JSON.stringify(responses)} operation=${JSON.stringify({ operationId: resumed.operationId, stage: resumed.stage, selection: resumed.selection, pagesCommitted: resumed.pagesCommitted })}`);

  await page.waitForFunction(async ({ documentId, operationId }) => {
    const response = await fetch(`/api/collections/default/documents/${documentId}/ocr/operations/${encodeURIComponent(operationId)}`);
    if (!response.ok) return false;
    const operation = await response.json();
    if (operation.stage === 'FAILED' || operation.stage === 'CANCELLED') throw new Error(`resume ended at ${operation.stage}: ${operation.errorCode} ${operation.errorMessage}`);
    return operation.stage === 'COMPLETE';
  }, { documentId: DOCUMENT_ID, operationId }, { timeout: 90_000 });
  await details.locator('button', { hasText: 'Open document' }).click();
  await review.waitFor({ timeout: 20_000 });
  const refreshLatest = review.getByRole('button', { name: 'Refresh to latest reading' });
  await refreshLatest.waitFor({ timeout: 20_000 });
  await refreshLatest.click();
  const textTab = review.getByRole('tab', { name: 'Text' });
  if (await textTab.count() > 0) await textTab.click();
  await review.getByRole('button', { name: 'Next page' }).click();
  await review.getByRole('button', { name: 'Next page' }).click();
  await review.getByText('Page 3 of 3', { exact: true }).waitFor({ timeout: 20_000 });
  await review.locator('.review-text').getByText('OCR result for scanned page', { exact: false }).waitFor({ timeout: 20_000 });
  const resumedPair = await review.evaluate((node) => {
    const image = node.querySelector('.image-pane img');
    return {
      text: node.querySelector('.review-text')?.textContent,
      revision: image ? new URL(image.getAttribute('src'), location.href).searchParams.get('revision') : null,
      ordinal: image ? new URL(image.getAttribute('src'), location.href).searchParams.get('ordinal') : null,
    };
  });
  const resumedOperation = await (await page.request.get(`${BASE_URL}/api/collections/default/documents/${DOCUMENT_ID}/ocr/operations/${encodeURIComponent(operationId)}`)).json();
  assert(resumedOperation.stage === 'COMPLETE' && resumedOperation.selection?.mode === 'explicit' && JSON.stringify(resumedOperation.selection.ordinals) === '[0,2]',
    `the same explicit operation completes with its frozen selection: ${JSON.stringify(resumedOperation)}`);
  assert(resumedOperation.pagesCommitted === 2, `the committed checkpoint plus remaining selected page are preserved: ${JSON.stringify(resumedOperation)}`);
  assert(resumedPair.text?.includes('OCR result for scanned page') && resumedPair.ordinal === '2' && resumedPair.revision === resumedOperation.candidateRevisionId,
    `resumed text is paired with selected page 3 in the candidate revision: ${JSON.stringify({ resumedPair, operation: resumedOperation })}`);
}

async function scenarioScannedTable(page) {
  // The scanned page has no text layer, so its words reach the reader and Ask only through the table structure.
  await page.waitForSelector('#tab-search', { timeout: 20_000 });
  await page.click('#tab-search');
  await page.fill('#query', 'Archive scanning');
  await page.locator('.result').first().waitFor({ timeout: 20_000 });
  await page.locator('.result').first().click();
  const source = page.locator('.source-pane');
  await source.waitFor({ timeout: 20_000 });
  const table = source.locator('table');
  await table.waitFor({ timeout: 20_000 });
  assert(await source.locator('th[scope="col"]', { hasText: 'Amount' }).count() === 1, 'the source shows the table header row as column headers');
  assert((await table.locator('td').allTextContents()).includes('1480 SEK'), 'the source shows the table cell values');
  assert((await source.textContent()).includes('Approved payments'), 'the source shows the heading above the table');
  console.log('PASS scanned-table-source headers=Item,Amount');
  console.log('MARK: table-source-checked');

  await page.click('#tab-ask');
  await page.waitForSelector('#question', { timeout: 20_000 });
  await page.fill('#question', 'What amount was approved for archive scanning?');
  await page.waitForFunction(() => !document.querySelector('#panel-ask button[type="submit"]')?.disabled, null, { timeout: 20_000 });
  await page.locator('#panel-ask button[type="submit"]').click();
  await page.locator('#panel-ask button.citation').waitFor({ timeout: 30_000 });
  assert((await page.locator('[aria-label="Answer"]').textContent()).includes('approved'), 'Ask shows the cited answer');
  console.log('PASS scanned-table-ask');
}

const browser = await chromium.launch({ headless: true });
try {
  const context = await browser.newContext();
  const page = await context.newPage();
  page.on('pageerror', (error) => { throw error; });
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
  if (SCENARIO === 'page-browser') await scenarioPageBrowser(page);
  else if (SCENARIO === 'page-resources') await scenarioPageResources(page);
  else if (SCENARIO === 'ask-old-citation') await scenarioAskOldCitation(page);
  else if (SCENARIO === 'ask-prompt-from-admin') await scenarioAskPromptFromAdmin(page);
  else if (SCENARIO === 'ask-image-old-citation') await scenarioAskImageOldCitation(page);
  else if (SCENARIO === 'ask-image-create') await scenarioAskImageCreate(page);
  else if (SCENARIO === 'ask-image-unknown-citation') await scenarioAskImageUnknownCitation(page);
  else if (SCENARIO === 'selectable-pdf') await scenarioSelectablePdf(page);
  else if (SCENARIO === 'page-selection') await scenarioPageSelection(page);
  else if (SCENARIO === 'page-selection-retry') await scenarioPageSelectionRetry(page);
  else if (SCENARIO === 'scanned-table') await scenarioScannedTable(page);
  else throw new Error(`unknown scenario ${SCENARIO}`);
  await context.close();
} finally {
  await browser.close();
}
