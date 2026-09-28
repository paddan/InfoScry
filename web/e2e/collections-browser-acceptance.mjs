/**
 * The real-browser acceptance boundary for the Collections workflow (ticket 12).
 *
 * It drives the actual built SvelteKit reader in Chromium against a local InfoScry server whose import
 * pipeline is the acceptance extractor and whose embedder is the deterministic test fake.
 * `CollectionsBrowserAcceptanceTest` owns the server, the temporary archive, the fixture files and the
 * picker, and sets BASE_URL, SCENARIO and FIXTURES for this script.
 *
 * Every scenario asserts on what the reader shows and exits non-zero on failure, so the Kotlin test only
 * has to check the process result, its own database facts, and the `MARK:` lines this script prints. A
 * mark is a handshake: the Kotlin side does something the browser cannot (release a held extraction)
 * between a mark and the step that depends on it.
 */
import { chromium } from 'playwright';

const BASE_URL = process.env.BASE_URL;
const SCENARIO = process.env.SCENARIO ?? 'collection-creation';
const FIXTURES = process.env.FIXTURES;

if (!BASE_URL) throw new Error('BASE_URL is required');
if (!FIXTURES) throw new Error('FIXTURES is required');

const PANEL = '#admin-panel-collections';

let activePage = null;

function fail(message) {
  throw new Error(message);
}

function assert(condition, message) {
  if (!condition) fail(message);
}

function mark(name) {
  console.log(`MARK: ${name}`);
}

/* ---- Navigation ---- */

async function enterAdmin(page) {
  await page.waitForSelector('#tab-admin', { timeout: 20_000 });
  await page.click('#tab-admin');
  await page.click('#admin-tab-collections');
  await page.waitForSelector(`${PANEL} .collections-panel`, { timeout: 20_000 });
}

async function openAdmin(browser) {
  const context = await browser.newContext();
  const page = await context.newPage();
  const consoleErrors = [];
  const failedRequests = [];
  /** Every source URL the reader fetched, so a saved link can be re-requested after a deletion. */
  const sourceUrls = [];
  /** The operation the server admitted for the last collection deletion. */
  let deleteOperationId = null;
  page.on('pageerror', (error) => consoleErrors.push(String(error)));
  page.on('console', (message) => {
    if (message.type() === 'error') consoleErrors.push(message.text());
  });
  page.on('response', (response) => {
    if (response.status() >= 400) failedRequests.push(`${response.status()} ${response.request().method()} ${response.url()}`);
    if (/\/sources\//.test(response.url())) sourceUrls.push(response.url());
    if (response.request().method() === 'DELETE' && /\/api\/collections\//.test(response.url())) {
      response
        .json()
        .then((body) => {
          if (body?.operationId) deleteOperationId = body.operationId;
        })
        .catch(() => {});
    }
  });
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
  await enterAdmin(page);
  activePage = page;
  return { context, page, consoleErrors, failedRequests, sourceUrls, deleteOperationId: () => deleteOperationId };
}

/** A reload or a navigation away and back: the archive's state has to come back from the server. */
async function reloadAdmin(page) {
  await page.goto(BASE_URL, { waitUntil: 'domcontentloaded' });
  await enterAdmin(page);
}

/**
 * A reload and the collection selected again.
 *
 * The panel reads its collection, its rows and its history when one is selected there, so an import
 * started through the Add documents form leaves them as they were until the reader asks again. That is
 * the same ask a reader makes by reopening Admin, and it is what the durable state has to answer.
 */
async function reloadAndSelect(page, name) {
  await reloadAdmin(page);
  await selectCollection(page, name);
}

/* ---- Collections ---- */

async function collectionNames(page) {
  return page.$$eval(`${PANEL} .collection-list li button .name`, (nodes) => nodes.map((node) => node.textContent.trim()));
}

async function waitForManagedCollection(page, name) {
  await page.waitForFunction(
    (expected) => {
      const active = document.querySelector('#admin-panel-collections .collection-list li button[aria-pressed="true"]');
      return !!active && (active.querySelector('.name')?.textContent ?? '').trim() === expected;
    },
    name,
    { timeout: 20_000 },
  );
}

async function selectCollection(page, name) {
  await page.locator(`${PANEL} .collection-list li button`, { hasText: name }).first().click();
  await waitForManagedCollection(page, name);
  // Selecting a collection starts its row and history reads; asserting on the panel before they answer
  // would read the loading state as an absence.
  await page.waitForTimeout(200);
  await page.waitForFunction(
    () => {
      const text = document.querySelector('#admin-panel-collections')?.textContent ?? '';
      return !text.includes('Loading documents…') && !text.includes('Loading imports…');
    },
    null,
    { timeout: 30_000 },
  );
}

async function createCollection(page, name) {
  await page.locator(`${PANEL} button`, { hasText: 'Create collection' }).first().click();
  await page.fill('#new-collection-name', name);
  await page.locator(`${PANEL} .collection-form button[type="submit"]`).click();
  await waitForManagedCollection(page, name);
}

async function panelText(page) {
  return (await page.textContent(PANEL)) ?? '';
}

async function deletionLines(page) {
  return page.$$eval(`${PANEL} .deletions li`, (nodes) => nodes.map((node) => node.textContent.trim()));
}

/* ---- Adding documents ---- */

async function chooseAndImport(page, kind) {
  await page.locator(`${PANEL} button`, { hasText: 'Add documents' }).first().click();
  await page.waitForSelector('.import-panel', { timeout: 20_000 });
  await page.locator('.import-panel button', { hasText: kind === 'folder' ? 'Choose folder' : 'Choose files' }).first().click();
  await page.waitForSelector('.import-panel .paths li', { timeout: 20_000 });
  await page.locator('.import-panel button', { hasText: 'Import' }).first().click();
}

async function waitForImportFinished(page, timeout = 60_000) {
  await page.waitForFunction(
    () => {
      const panel = document.querySelector('.import-panel');
      if (!panel) return false;
      const status = Array.from(panel.querySelectorAll('p[role="status"]'))
        .map((node) => node.textContent ?? '')
        .join(' ');
      return /Import complete|Import failed|Import cancelled/.test(status);
    },
    null,
    { timeout },
  );
  return (await page.textContent('.import-panel')) ?? '';
}

/**
 * The add form's own status line says an import is under way.
 *
 * The line names the file being read once a file is named, so the match is on the verb and never on the
 * literal ellipsis: a wait that required `Importing…` would only ever match the instant before the first
 * file is named, which a reader (or this suite) can miss.
 */
async function waitForImportRunning(page) {
  await page.waitForFunction(
    () => {
      const status = Array.from(document.querySelectorAll('.import-panel p[role="status"]'))
        .map((node) => node.textContent ?? '')
        .join(' ');
      return /Importing/.test(status);
    },
    null,
    { timeout: 30_000 },
  );
}

async function importResults(page) {
  return page.$$eval('.import-panel table.results tbody tr', (rows) =>
    rows.map((row) => {
      const cells = row.querySelectorAll('td');
      return { source: cells[0].textContent.trim(), outcome: cells[1].textContent.trim() };
    }),
  );
}

/* ---- The document table ---- */

async function rows(page) {
  return page.$$eval(`${PANEL} table.documents tbody tr`, (trs) =>
    trs.map((row) => {
      const cells = row.querySelectorAll('td, th');
      return {
        filename: cells[1].textContent.trim(),
        status: cells[5].textContent.trim(),
        progress: cells[6].textContent.trim(),
      };
    }),
  );
}

async function rowOf(page, filename) {
  return (await rows(page)).find((row) => row.filename === filename) ?? null;
}

async function waitForRow(page, filename, timeout = 60_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if ((await rowOf(page, filename)) !== null) return;
    await page.waitForTimeout(300);
  }
  fail(`${filename} never appeared in the document table`);
}

async function waitForRowGone(page, filename, timeout = 60_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    if ((await rowOf(page, filename)) === null) return;
    await page.waitForTimeout(300);
  }
  fail(`${filename} was still listed after ${timeout} ms`);
}

/** A user's own refresh: submitting the (possibly empty) filename search re-reads the rows. */
async function refreshRows(page) {
  await page.locator(`${PANEL} .document-search button[type="submit"]`).click();
  await page.waitForTimeout(150);
}

async function waitForStatus(page, filename, status, timeout = 90_000) {
  const deadline = Date.now() + timeout;
  let last = null;
  while (Date.now() < deadline) {
    await refreshRows(page);
    last = await rowOf(page, filename);
    if (last !== null && last.status === status) return last;
    await page.waitForTimeout(500);
  }
  fail(`${filename} never reached ${status}; last row was ${JSON.stringify(last)}`);
}

async function pagingText(page) {
  return (await page.locator(`${PANEL} .paging`).first().textContent())?.replace(/\s+/g, ' ').trim() ?? '';
}

/* ---- Details and the reader ---- */

async function openDetails(page, filename) {
  await page.locator(`${PANEL} table.documents tbody tr`, { hasText: filename }).locator('button', { hasText: 'Details' }).first().click();
  await page.waitForSelector(`${PANEL} .document-details`, { timeout: 20_000 });
  await page.waitForTimeout(150);
}

async function detailFacts(page) {
  return page.$$eval(`${PANEL} .document-details dl`, (lists) => {
    const facts = {};
    for (const list of lists) {
      const children = Array.from(list.children);
      for (let index = 0; index + 1 < children.length; index += 2) {
        facts[children[index].textContent.trim()] = children[index + 1].textContent.trim();
      }
    }
    return facts;
  });
}

async function closeDetails(page) {
  await page.locator(`${PANEL} .document-details button`, { hasText: 'Close details' }).click();
  await page.waitForSelector(`${PANEL} .document-details`, { state: 'detached' });
}

/* ---- Import history ---- */

async function importHistory(page) {
  return page.$$eval(`${PANEL} .import-history > .table-scroll > table.imports tbody tr`, (trs) =>
    trs.map((row) => {
      const cells = row.querySelectorAll('td, th');
      return {
        state: cells[1].textContent.trim(),
        stage: cells[2].textContent.trim(),
        files: cells[3].textContent.trim(),
      };
    }),
  );
}

async function showFiles(page, rowIndex) {
  await page.locator(`${PANEL} .import-history > .table-scroll > table.imports tbody tr`).nth(rowIndex).locator('button').click();
  await page.waitForSelector(`${PANEL} .import-items`, { timeout: 20_000 });
  await page.waitForTimeout(200);
  return page.$$eval(`${PANEL} .import-items tbody tr`, (trs) =>
    trs.map((row) => {
      const cells = row.querySelectorAll('td, th');
      return { file: cells[0].textContent.trim(), outcome: cells[1].textContent.trim() };
    }),
  );
}

/** The source URL a saved historical link would carry, recorded from the reader's own read. */
function lastSourceUrl(sourceUrls) {
  assert(sourceUrls.length > 0, 'the reader must have read a source before a link can be re-requested');
  return sourceUrls[sourceUrls.length - 1];
}


async function deleteCollection(page, name) {
  await page.locator(`${PANEL} button`, { hasText: 'Delete collection' }).first().click();
  await page.waitForSelector('#delete-collection-heading', { timeout: 20_000 });
  await page.fill('#delete-collection-name', name);
  await page.locator(`${PANEL} .confirm-dialog button[type="submit"]`).click();
  await page.waitForSelector('#delete-collection-heading', { state: 'detached', timeout: 20_000 });
}

async function deleteDocument(page, filename) {
  await page.locator(`${PANEL} button[aria-label="Delete ${filename}"]`).click();
  await page.waitForSelector('#delete-document-heading', { timeout: 20_000 });
  await page.locator(`${PANEL} .confirm-dialog button[type="submit"]`).click();
  await page.waitForSelector('#delete-document-heading', { state: 'detached', timeout: 20_000 });
}

/** The operation the server admitted for a collection deletion, once its 202 has been read. */
async function waitForDeleteOperation(operationId, timeout = 20_000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) {
    const id = operationId();
    if (id) return id;
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  fail('the collection deletion was not answered with an operation id');
}

/* ---- Scenarios ---- */

const scenarios = {
  /** An empty archive, creation inside Admin, and a layout/keyboard pass over the same panel. */
  async 'collection-creation'(browser) {
    const { page, consoleErrors } = await openAdmin(browser);
    const empty = await panelText(page);
    assert(empty.includes('No collections yet'), 'an archive with no collections must say so');
    assert((await collectionNames(page)).length === 0, 'an empty archive must offer no collection');
    assert(empty.includes('every import goes into the collection you choose'), 'the empty state must explain what collections are for');

    await createCollection(page, 'Notes');
    assert((await collectionNames(page)).includes('Notes'), 'the created collection must be listed');
    assert((await panelText(page)).includes('No documents yet'), 'a new collection must show an empty document list');
    await page.waitForFunction(
      () => Array.from(document.querySelectorAll('#collection option')).some((option) => option.textContent.trim() === 'Notes'),
      null,
      { timeout: 20_000 },
    );
    assert(
      (await page.$$eval('#collection option', (options) => options.map((option) => option.textContent.trim()))).includes('Notes'),
      'creating a collection must refresh the workspace collection selector',
    );

    // The exact-name confirmation opens focused on the name field, and declining returns focus to the
    // control that opened it.
    await page.locator(`${PANEL} button`, { hasText: 'Delete collection' }).first().click();
    await page.waitForSelector('#delete-collection-heading', { timeout: 20_000 });
    const focused = await page.evaluate(() => document.activeElement?.id ?? '');
    assert(focused === 'delete-collection-name', `the confirmation must focus the name field, got ${JSON.stringify(focused)}`);
    await page.keyboard.press('Escape');
    await page.waitForSelector('#delete-collection-heading', { state: 'detached', timeout: 20_000 });
    const restored = await page.evaluate(() => (document.activeElement?.textContent ?? '').trim());
    assert(restored === 'Delete collection', `declining must return focus to the opener, got ${JSON.stringify(restored)}`);
    assert((await collectionNames(page)).includes('Notes'), 'declining a deletion must remove nothing');

    // A narrow window: the workspace collapses to one column and the wide document table scrolls inside
    // the panel instead of pushing the page sideways.
    await page.setViewportSize({ width: 420, height: 820 });
    await page.reload({ waitUntil: 'domcontentloaded' });
    await enterAdmin(page);
    await selectCollection(page, 'Notes');
    const layout = await page.evaluate(() => {
      const tablist = document.querySelector('.mode-nav [role="tablist"]');
      const scroller = document.querySelector('#admin-panel-collections .table-scroll');
      return {
        tabDisplay: tablist === null ? '' : getComputedStyle(tablist).display,
        pageOverflow: document.documentElement.scrollWidth - window.innerWidth,
        scrollerOverflow: scroller === null ? -1 : scroller.scrollWidth - scroller.clientWidth,
      };
    });
    assert(layout.tabDisplay === 'flex', `the narrow layout must lay the mode tabs out in one row, got ${layout.tabDisplay}`);
    assert(layout.pageOverflow <= 2, `a narrow window must not scroll the page sideways, overflow ${layout.pageOverflow}px`);
    assert((await panelText(page)).includes('No documents yet'), 'the narrow layout must keep the panel usable');
    await page.setViewportSize({ width: 1280, height: 900 });

    // Managing collections leaves the LLM profiles sub-tab and its profiles alone.
    await page.click('#admin-tab-llm');
    await page.waitForSelector('#admin-panel-llm', { timeout: 20_000 });
    await page.waitForFunction(
      () => (document.querySelector('#admin-panel-llm')?.textContent ?? '').includes('acceptance-profile'),
      null,
      { timeout: 20_000 },
    );

    // Reloading keeps the created collection; the workspace selection survives with it.
    await reloadAdmin(page);
    await selectCollection(page, 'Notes');
    assert((await collectionNames(page)).includes('Notes'), 'the created collection must survive a reload');
    assert((await panelText(page)).includes('No documents yet'), 'an empty collection must read the same after a reload');

    const relevantErrors = consoleErrors.filter((text) => !text.includes('Failed to load resource'));
    assert(relevantErrors.length === 0, `unexpected console errors: ${relevantErrors.join(' | ')}`);
  },

  /** The legacy Default: kept while it holds material, explained, and removed only by choice. */
  async 'legacy-default'(browser) {
    const { page, consoleErrors } = await openAdmin(browser);
    const names = await collectionNames(page);
    assert(names.includes('Default') && names.includes('Notes'), `a used legacy Default must be kept, got ${JSON.stringify(names)}`);
    await selectCollection(page, 'Default');
    const text = await panelText(page);
    assert(text.includes('FORMER DEFAULT COLLECTION'), 'the legacy collection must be explained where it is managed');
    assert(text.includes('Earlier InfoScry versions created this collection automatically'), 'the explanation must say where it came from');
    assert(text.includes('rename it under Settings'), 'the explanation must offer the rename');
    assert(text.includes('delete it after confirming its exact name'), 'the explanation must offer the explicit deletion');
    await waitForRow(page, 'legacy-document.txt');

    await page.fill('#collection-settings-name', 'Old notes');
    await page.locator(`${PANEL} .settings-form button`, { hasText: 'Save name' }).click();
    await page.waitForFunction(
      () => /Name saved\./.test(document.querySelector('#admin-panel-collections')?.textContent ?? ''),
      null,
      { timeout: 20_000 },
    );
    await waitForManagedCollection(page, 'Old notes');
    assert((await panelText(page)).includes('FORMER DEFAULT COLLECTION'), 'the renamed legacy collection is still the legacy one');
    await waitForRow(page, 'legacy-document.txt');

    await deleteCollection(page, 'Old notes');
    assert(!(await collectionNames(page)).includes('Old notes'), 'the legacy collection must be removable by choice');

    // A collection the reader names Default themselves is not the old seed: it has its own id, and that
    // is what the notice is keyed on.
    await createCollection(page, 'Default');
    assert(
      !(await panelText(page)).includes('FORMER DEFAULT COLLECTION'),
      'a collection the reader named Default must not be treated as the legacy one',
    );
    assert((await panelText(page)).includes('No documents yet'), 'the reader\'s own collection starts empty');

    const relevantErrors = consoleErrors.filter((text) => !text.includes('Failed to load resource'));
    assert(relevantErrors.length === 0, `unexpected console errors: ${relevantErrors.join(' | ')}`);
  },

  /** Folder import, duplicates, filtered and paginated listing, and durable history after a reload. */
  async 'documents-and-history'(browser) {
    const { page, consoleErrors } = await openAdmin(browser);
    await selectCollection(page, 'Notes');
    await chooseAndImport(page, 'folder');
    const finished = await waitForImportFinished(page);
    assert(/Import complete/.test(finished), `importing the fixture folder must finish, panel said ${JSON.stringify(finished)}`);
    const bulkResults = await importResults(page);
    assert(bulkResults.length === 55, `the import results must list 55 files, got ${bulkResults.length}`);
    assert(bulkResults.every((item) => item.outcome === 'Imported'), 'every fixture file must be reported as imported');

    await reloadAndSelect(page, 'Notes');
    // Newest first: the folder was imported in name order, so the last fixture is the newest document.
    await waitForRow(page, 'note-55.txt');
    assert((await rows(page)).length === 50, 'the first page must show at most 50 documents');
    const firstPage = await pagingText(page);
    assert(firstPage.includes('Showing 1–50 of 55 documents'), `the first page must say what it shows, got ${JSON.stringify(firstPage)}`);

    await page.locator(`${PANEL} .paging button`, { hasText: 'Next page' }).first().click();
    await page.waitForFunction(
      () => /Showing 51–55 of 55\s+documents/.test((document.querySelector('#admin-panel-collections')?.textContent ?? '').replace(/\s+/g, ' ')),
      null,
      { timeout: 20_000 },
    );
    assert((await rows(page)).length === 5, 'the second page must show the remaining five documents');
    await page.locator(`${PANEL} .paging button`, { hasText: 'Previous page' }).first().click();
    await page.waitForFunction(
      () => /Showing 1–50 of 55\s+documents/.test((document.querySelector('#admin-panel-collections')?.textContent ?? '').replace(/\s+/g, ' ')),
      null,
      { timeout: 20_000 },
    );

    // A filename search filters on the server, and the total describes the same rows.
    await page.fill('#document-search', 'note-1');
    await refreshRows(page);
    assert((await rows(page)).length === 10, 'the filename search must match only the ten note-1x fixtures');
    assert((await pagingText(page)).includes('of 10'), 'the filtered total must describe the filtered rows');
    await page.fill('#document-search', '');
    await refreshRows(page);
    assert((await rows(page)).length === 50, 'clearing the search must come back to the first page');

    // A status filter, including one that matches nothing.
    await page.selectOption('#document-status', 'COMPLETE');
    await page.waitForTimeout(200);
    assert((await pagingText(page)).includes('of 55'), 'the complete status must match every imported fixture');
    await page.selectOption('#document-status', 'FAILED');
    await page.waitForTimeout(200);
    assert((await panelText(page)).includes('No documents match this search.'), 'a filter nothing matches must say so');
    await page.selectOption('#document-status', '');
    await page.waitForTimeout(200);

    // The sort order is the server's, and it is stable.
    await page.selectOption('#document-sort', 'name-desc');
    await page.waitForTimeout(200);
    assert((await rows(page))[0].filename === 'note-55.txt', 'name Z to A must put the last fixture first');
    await page.selectOption('#document-sort', 'newest');
    await page.waitForTimeout(200);

    // The same bytes twice: the second and third are duplicates, not documents.
    await chooseAndImport(page, 'files');
    const duplicatesFinished = await waitForImportFinished(page);
    assert(/Import complete/.test(duplicatesFinished), `the duplicate import must finish, panel said ${JSON.stringify(duplicatesFinished)}`);
    const duplicateResults = await importResults(page);
    assert(duplicateResults.length === 3, `three files were selected, got ${duplicateResults.length} results`);
    assert(duplicateResults.filter((item) => item.outcome === 'Duplicate').length === 2, 'two of the identical files must be duplicates');
    await reloadAndSelect(page, 'Notes');
    await refreshRows(page);
    assert((await pagingText(page)).includes('of 56 documents'), 'only one of the three identical files may become a document');
    await waitForRow(page, 'dup-a.txt');

    // The import history is the server's, so a reload shows both imports, their counters and their
    // per-file outcomes again.
    const history = await importHistory(page);
    assert(history.length === 2, `two imports must be listed, got ${history.length}`);
    assert(history[0].state === 'Complete', `the newest import must read Complete, got ${history[0].state}`);
    assert(history[0].files === '3 of 3 files', `file counters are files, got ${history[0].files}`);
    assert(history[1].files === '55 of 55 files', `the folder import must count 55 files, got ${history[1].files}`);
    const perFile = await showFiles(page, 0);
    assert(perFile.filter((item) => item.outcome === 'Duplicate').length === 2, 'the per-file results must report duplicates in words');

    await reloadAndSelect(page, 'Notes');
    await waitForRow(page, 'dup-a.txt');
    assert((await importHistory(page)).length === 2, 'reloading must not lose an import');
    const perFileAfterReload = await showFiles(page, 0);
    assert(perFileAfterReload.filter((item) => item.outcome === 'Duplicate').length === 2, 'the duplicate outcomes must survive a reload');
    assert((await pagingText(page)).includes('of 56 documents'), 'the document count must survive a reload');

    // A narrow window scrolls the wide table inside the panel rather than pushing the page sideways.
    await page.setViewportSize({ width: 420, height: 820 });
    await refreshRows(page);
    const narrow = await page.evaluate(() => {
      const scroller = document.querySelector('#admin-panel-collections .table-scroll');
      return {
        pageOverflow: document.documentElement.scrollWidth - window.innerWidth,
        tableOverflow: scroller === null ? -1 : scroller.scrollWidth - scroller.clientWidth,
      };
    });
    assert(narrow.tableOverflow > 0, `the table must scroll inside the panel on a narrow window, got ${narrow.tableOverflow}`);
    assert(narrow.pageOverflow <= 2, `a narrow window must not scroll the page sideways, overflow ${narrow.pageOverflow}px`);
    await page.setViewportSize({ width: 1280, height: 900 });

    const relevantErrors = consoleErrors.filter((text) => !text.includes('Failed to load resource'));
    assert(relevantErrors.length === 0, `unexpected console errors: ${relevantErrors.join(' | ')}`);
  },

  /** Honest progress: a mixed direct-text/OCR document, an unknown total, and legacy unknown units. */  async 'document-details-and-reader'(browser) {
    const { page, consoleErrors } = await openAdmin(browser);
    await selectCollection(page, 'Notes');
    await chooseAndImport(page, 'files');
    const finished = await waitForImportFinished(page);
    assert(/Import complete/.test(finished), `the fixture import must finish, panel said ${JSON.stringify(finished)}`);
    await reloadAndSelect(page, 'Notes');
    await refreshRows(page);

    const mixed = await rowOf(page, 'mixed-pages.png');
    assert(mixed !== null, 'the mixed fixture must be listed');
    assert(mixed.progress === '3/3 pages', `an announced page total is a page count, got ${JSON.stringify(mixed.progress)}`);
    assert(mixed.status === 'Complete', `the mixed fixture must complete, got ${mixed.status}`);

    await openDetails(page, 'mixed-pages.png');
    const mixedFacts = await detailFacts(page);
    assert(mixedFacts.Progress === '3/3 pages processed', `the detail must name the announced total, got ${JSON.stringify(mixedFacts.Progress)}`);
    assert(mixedFacts['Read directly'] === '1 page', `one page had its own text, got ${JSON.stringify(mixedFacts['Read directly'])}`);
    assert(mixedFacts['Read by OCR'] === '2 pages', `two pages were read by a tool, got ${JSON.stringify(mixedFacts['Read by OCR'])}`);
    const detailsText = await page.textContent(`${PANEL} .document-details`);
    assert(detailsText.includes('counts every page of the document'), 'the page denominator must be explained rather than implied');
    assert((await page.locator('#source-heading').count()) === 0, 'the reader must only open when it is asked to');

    // The reader opens at the first available unit of the document.
    await page.locator(`${PANEL} .document-details button`, { hasText: 'Open document' }).click();
    await page.waitForSelector('#source-heading', { timeout: 20_000 });
    await page.waitForSelector('pre.source-text', { timeout: 20_000 });
    const opened = (await page.textContent('pre.source-text')) ?? '';
    assert(opened.includes('Page 1 of the scanned acceptance fixture.'), `the reader must show the first unit, got ${JSON.stringify(opened)}`);
    await page.click('button[aria-label="Close source viewer"]');
    await page.waitForSelector('#source-heading', { state: 'detached' });
    await closeDetails(page);

    // A legacy document whose summary is all the archive has: the method is unknown, not zero, and the
    // unit it counts is named neutrally because the archive never recorded what one unit was.
    const legacy = await rowOf(page, 'legacy-summary.txt');
    assert(legacy !== null, 'the seeded legacy document must be listed');
    assert(legacy.progress === '0/7 units', `unknown unit kinds must read as units, got ${JSON.stringify(legacy.progress)}`);
    await openDetails(page, 'legacy-summary.txt');
    const legacyFacts = await detailFacts(page);
    assert(legacyFacts.Progress === '0/7 units processed', `a legacy total must keep its cut-off facts, got ${JSON.stringify(legacyFacts.Progress)}`);
    const legacyText = await page.textContent(`${PANEL} .document-details`);
    assert(!legacyText.includes('Read directly'), 'an unrecorded method must be left out rather than shown as zero');
    assert(legacyText.includes('no readable content yet'), 'a document with nothing to read must say why the reader is unavailable');
    assert((await page.locator(`${PANEL} .document-details button`, { hasText: 'Open document' }).count()) === 0, 'nothing to open must offer no open button');
    await closeDetails(page);

    // An extractor that never announced a total: the count is the honest thing to show, with no
    // invented denominator and no noun the archive never recorded.
    await openDetails(page, 'one.txt');
    const textFacts = await detailFacts(page);
    assert(textFacts.Progress === '1 line processed', `an unannounced total must stay a count, got ${JSON.stringify(textFacts.Progress)}`);
    await closeDetails(page);

    await reloadAndSelect(page, 'Notes');
    await waitForRow(page, 'mixed-pages.png');
    assert((await rowOf(page, 'mixed-pages.png')).progress === '3/3 pages', 'progress must survive a reload');

    const relevantErrors = consoleErrors.filter((text) => !text.includes('Failed to load resource'));
    assert(relevantErrors.length === 0, `unexpected console errors: ${relevantErrors.join(' | ')}`);
  },

  /** Settings, a single Retry, and Retry all, all on the managed collection. */
  async 'settings-and-retry'(browser) {
    const { page, consoleErrors } = await openAdmin(browser);
    await selectCollection(page, 'Notes');
    await chooseAndImport(page, 'files');
    await waitForImportFinished(page);
    await reloadAndSelect(page, 'Notes');
    await waitForStatus(page, 'fail-once-a.txt', 'Failed');
    await waitForStatus(page, 'fail-once-b.txt', 'Failed');

    // One document, read again from the copy InfoScry holds: its first attempt failed, its second reads.
    await openDetails(page, 'fail-once-a.txt');
    const failedFacts = await detailFacts(page);
    assert(failedFacts.Status === 'Failed', `the detail must show the failed status, got ${JSON.stringify(failedFacts.Status)}`);
    const failedText = await page.textContent(`${PANEL} .document-details`);
    assert(/[Ss]omething|fail|could not|refused/.test(failedText), 'a failed document must explain itself in words');
    await page.locator(`${PANEL} .document-details button`, { hasText: 'Retry' }).click();
    // The queued sentence belongs to the eligible-document block, so it goes away with the retry
    // itself; the attempt the server queued is what stays visible, in the row's own status.
    await waitForStatus(page, 'fail-once-a.txt', 'Complete');
    assert((await rowOf(page, 'fail-once-b.txt')).status === 'Failed', 'retrying one document must leave the other alone');

    // Renaming and the OCR languages are the collection's own settings, and they survive a reload.
    await page.fill('#collection-settings-name', 'Notes renamed');
    await page.locator(`${PANEL} .settings-form button`, { hasText: 'Save name' }).click();
    await page.waitForFunction(() => (document.querySelector('#admin-panel-collections')?.textContent ?? '').includes('Name saved.'), null, { timeout: 20_000 });
    assert((await collectionNames(page)).includes('Notes renamed'), 'the renamed collection must be listed under its new name');
    await page.waitForFunction(
      () => Array.from(document.querySelectorAll('#collection option')).some((option) => option.textContent.trim() === 'Notes renamed'),
      null,
      { timeout: 20_000 },
    );

    // A typed refusal is readable, and it changes nothing: the name another collection already uses is a
    // conflict the reader can act on rather than a silent no-op.
    await page.fill('#collection-settings-name', 'Archive');
    await page.locator(`${PANEL} .settings-form button`, { hasText: 'Save name' }).click();
    await page.waitForFunction(
      () => /already exists/.test(document.querySelector('#admin-panel-collections')?.textContent ?? ''),
      null,
      { timeout: 20_000 },
    );
    const renameAlert = (await page.locator(`${PANEL} .settings-form`).first().locator('[role="alert"]').textContent()) ?? '';
    assert(renameAlert.includes('Archive') && renameAlert.includes('already exists'), `the refusal must be readable, got ${JSON.stringify(renameAlert)}`);
    assert((await collectionNames(page)).includes('Notes renamed'), 'a refused rename must leave the collection as it was');

    await page.fill('#collection-settings-ocr-languages', 'eng+deu');
    await page.locator(`${PANEL} .settings-form button`, { hasText: 'Save OCR languages' }).click();
    await page.waitForFunction(
      () => (document.querySelector('#admin-panel-collections')?.textContent ?? '').includes('OCR languages saved.'),
      null,
      { timeout: 20_000 },
    );
    await reloadAndSelect(page, 'Notes renamed');
    assert(await page.inputValue('#collection-settings-name') === 'Notes renamed', 'the saved name must come back after a reload');
    assert(await page.inputValue('#collection-settings-ocr-languages') === 'eng+deu', 'the saved OCR languages must come back after a reload');


    // Every eligible document, wherever it is listed. The OCR languages changed since this document's
    // first attempt, so its reading is repeated rather than reused; the panel says that such a retry may
    // have to repeat extraction, and the acceptance extractor records which attempt read what.
    await page.locator(`${PANEL} button`, { hasText: 'Retry all eligible documents' }).click();
    await page.waitForFunction(
      () => (document.querySelector('#admin-panel-collections')?.textContent ?? '').includes('Retry queued for every eligible document'),
      null,
      { timeout: 20_000 },
    );
    await waitForStatus(page, 'fail-once-b.txt', 'Complete');

    // With nothing eligible the collection-wide action says so instead of counting a success.
    await page.locator(`${PANEL} button`, { hasText: 'Retry all eligible documents' }).click();
    await page.waitForFunction(
      () => (document.querySelector('#admin-panel-collections')?.textContent ?? '').includes('Nothing to retry'),
      null,
      { timeout: 20_000 },
    );

    const relevantErrors = consoleErrors.filter((text) => !text.includes('Failed to load resource'));
    assert(relevantErrors.length === 0, `unexpected console errors: ${relevantErrors.join(' | ')}`);
  },

  /** One document, several documents, and the viewer of a document that is removed. */
  async 'document-deletion'(browser) {
    const { page, consoleErrors, sourceUrls } = await openAdmin(browser);
    await selectCollection(page, 'Notes');
    await chooseAndImport(page, 'files');
    await waitForImportFinished(page);
    await reloadAndSelect(page, 'Notes');
    for (const name of ['one.txt', 'two.txt', 'three.txt', 'four.txt']) await waitForRow(page, name);
    assert((await pagingText(page)).includes('of 4 documents'), 'the four fixtures must be the four documents');

    // One document, from its own row, after an explicit confirmation.
    await deleteDocument(page, 'one.txt');
    await waitForRowGone(page, 'one.txt');
    assert((await pagingText(page)).includes('of 3 documents'), 'deleting one document must lower the count');

    // A document open in the reader: the viewer closes once the removal is finished. The source sheet
    // is modal, so the deletion reaches the panel the way another client's admitted deletion does.
    await page.locator(`${PANEL} table.documents tbody tr`, { hasText: 'two.txt' }).locator('button', { hasText: 'Details' }).click();
    await page.waitForSelector(`${PANEL} .document-details`, { timeout: 20_000 });
    await page.locator(`${PANEL} .document-details button`, { hasText: 'Open document' }).click();
    await page.waitForSelector('#source-heading', { timeout: 20_000 });
    await page.waitForSelector('pre.source-text', { timeout: 20_000 });
    const twoText = (await page.textContent('pre.source-text')) ?? '';
    assert(twoText.includes('Line 1 of two.txt.'), `the reader must show the deleted document's own text, got ${JSON.stringify(twoText)}`);
    await page.evaluate(() => document.querySelector('#admin-panel-collections button[aria-label="Delete two.txt"]')?.click());
    await page.waitForSelector('#delete-document-heading', { timeout: 20_000 });
    await page.locator(`${PANEL} .confirm-dialog button[type="submit"]`).click();
    await page.waitForSelector('#source-heading', { state: 'detached', timeout: 30_000 });
    await waitForRowGone(page, 'two.txt');

    // Several documents, from the page-scoped selection.
    await page.click(`${PANEL} input[aria-label="Select three.txt"]`);
    await page.click(`${PANEL} input[aria-label="Select four.txt"]`);
    assert((await panelText(page)).includes('2 selected on this page.'), 'the selection must say how much of the page it holds');
    await page.locator(`${PANEL} button`, { hasText: 'Delete selected' }).click();
    await page.waitForSelector('#delete-document-heading', { timeout: 20_000 });
    const heading = (await page.textContent('#delete-document-heading')) ?? '';
    assert(heading.includes('2 documents'), `the confirmation must count the selected documents, got ${JSON.stringify(heading)}`);
    assert((await page.textContent(`${PANEL} .confirm-dialog`)).includes('Notes'), 'the confirmation must name the collection');
    await page.locator(`${PANEL} .confirm-dialog button[type="submit"]`).click();
    await waitForRowGone(page, 'three.txt');
    await waitForRowGone(page, 'four.txt');
    assert((await panelText(page)).includes('No documents yet'), 'removing every document must come back to the empty state');

    // A saved historical source link reports unavailable content rather than resurrecting the document:
    // the same URL the reader fetched while the document existed is re-requested after its removal.
    const stale = await page.request.get(lastSourceUrl(sourceUrls));
    assert(stale.status() === 404, `a saved source link must answer 404 after the document is gone, got ${stale.status()}`);
    const staleBody = await stale.json();
    assert(staleBody.error?.code === 'NOT_FOUND', `the refusal must be typed, got ${JSON.stringify(staleBody)}`);

    // The other collection is untouched by any of this.
    await selectCollection(page, 'Archive');
    for (const name of ['keep-a.txt', 'keep-b.txt']) await waitForRow(page, name);
    assert((await pagingText(page)).includes('of 2 documents'), 'another collection must keep its own documents');

    const relevantErrors = consoleErrors.filter((text) => !text.includes('Failed to load resource'));
    assert(relevantErrors.length === 0, `unexpected console errors: ${relevantErrors.join(' | ')}`);
  },

  /** A collection deleted while its import is still reading: pending status, reload, and recovery. */
  async 'collection-deletion-during-import'(browser) {
    const { page, consoleErrors, deleteOperationId } = await openAdmin(browser);
    await selectCollection(page, 'Notes');
    await chooseAndImport(page, 'folder');
    // The extraction stops between two units until the test releases it, so the add form keeps saying
    // it is importing while the archive already holds the first committed unit.
    await waitForImportRunning(page);
    mark('import-running');

    // A reload in the middle: the running import and the document's committed progress come back from
    // the server, not from the panel's memory.
    await reloadAndSelect(page, 'Notes');
    await waitForRow(page, 'held-01.txt');
    const heldAfterReload = await rowOf(page, 'held-01.txt');
    assert(heldAfterReload.status === 'Extracting text', `the held document must read as extracting, got ${heldAfterReload.status}`);
    assert(heldAfterReload.progress === '1 line processed', `an unannounced total must stay a count, got ${JSON.stringify(heldAfterReload.progress)}`);
    const historyWhileRunning = await importHistory(page);
    assert(historyWhileRunning.some((entry) => entry.state === 'Running'), `a reload must show the running import, got ${JSON.stringify(historyWhileRunning)}`);
    assert(historyWhileRunning.some((entry) => entry.files === '0 of 3 files'), `the file counters must be durable too, got ${JSON.stringify(historyWhileRunning)}`);
    // The exact-name confirmation, while the import is still running.
    await deleteCollection(page, 'Notes');
    let pending = await deletionLines(page);
    assert(pending.some((line) => line.includes('Deleting Notes')), `the admitted deletion must say it is deleting, got ${JSON.stringify(pending)}`);
    mark('deletion-pending');

    // The status is the server's: a reload restores the unfinished operation.
    await reloadAdmin(page);
    pending = await deletionLines(page);
    assert(pending.some((line) => line.includes('Deleting Notes')), `reopening Admin must restore the unfinished deletion, got ${JSON.stringify(pending)}`);
    mark('deletion-pending-restored');

    // The Kotlin side releases the held extraction here; the deletion finishes once the import stops.
    await page.waitForFunction(
      () => !(document.querySelector('#admin-panel-collections')?.textContent ?? '').includes('Deleting Notes'),
      null,
      { timeout: 90_000 },
    );
    // The read has to keep working after the collection row is gone; it is also what a caller follows
    // the operation with. Marked before the read so the held extraction is released first.
    const operationId = await waitForDeleteOperation(deleteOperationId);
    assert(!(await collectionNames(page)).includes('Notes'), 'the deleted collection must leave the manage list');
    assert((await collectionNames(page)).includes('Archive'), 'another collection must survive a deletion beside it');
    const operation = await (await page.request.get(`${BASE_URL}/api/deletions/${operationId}`)).json();
    assert(operation.operation.terminal === true, `the deletion must be finished, got ${JSON.stringify(operation)}`);
    assert(operation.operation.phase === 'DONE', `the deletion must reach DONE, got ${operation.operation.phase}`);
    assert((operation.operation.errorCode ?? null) === null, 'a finished deletion must carry no error code');
    await selectCollection(page, 'Archive');
    for (const name of ['keep-a.txt', 'keep-b.txt']) await waitForRow(page, name);
    assert((await panelText(page)).includes('No documents yet') === false, 'the untouched collection must keep its documents');
    mark('collection-deleted');

    const relevantErrors = consoleErrors.filter((text) => !text.includes('Failed to load resource'));
    assert(relevantErrors.length === 0, `unexpected console errors: ${relevantErrors.join(' | ')}`);
  },

  /** One document deleted while its own multi-file import is running, then a restart. */
  async 'multi-file-continuation'(browser) {
    const { page, consoleErrors } = await openAdmin(browser);
    await selectCollection(page, 'Notes');
    await chooseAndImport(page, 'folder');
    await waitForImportRunning(page);
    mark('import-running');
    await reloadAndSelect(page, 'Notes');
    await waitForRow(page, 'held-01.txt');
    const held = await rowOf(page, 'held-01.txt');
    assert(held.status === 'Extracting text', `the held document must read as extracting, got ${held.status}`);

    await deleteDocument(page, 'held-01.txt');
    await waitForRowGone(page, 'held-01.txt');
    mark('document-deleted');

    // The Kotlin side releases the held extraction here: the import must finish the remaining files and
    // must not copy the deleted one again.
    // The add form is closed and the import is the archive's now, so its state is read where it
    // survives a reload: the import history's own row.
    await page.waitForFunction(
      () => {
        const rows = document.querySelectorAll('#admin-panel-collections .import-history > .table-scroll > table.imports tbody tr');
        return Array.from(rows).some((row) => /^(Complete|Failed|Cancelled)/.test((row.querySelectorAll('td, th')[1]?.textContent ?? '').trim()));
      },
      null,
      { timeout: 90_000 },
    ).catch(async () => {
      const history = await importHistory(page);
      fail(`the multi-file import did not finish: ${JSON.stringify(history)}`);
    });
    await waitForStatus(page, 'held-02.txt', 'Complete');
    await waitForStatus(page, 'held-03.txt', 'Complete');
    await waitForRowGone(page, 'held-01.txt');
    const perFile = await showFiles(page, 0);
    const deleted = perFile.find((item) => item.file.includes('held-01.txt'));
    assert(deleted !== undefined, `the removed file must stay in the import's own record, got ${JSON.stringify(perFile)}`);
    assert(deleted.outcome === 'Cancelled', `the removed file must not read as imported, got ${JSON.stringify(deleted.outcome)}`);
    assert(perFile.filter((item) => item.outcome === 'Imported').length === 2, 'the remaining files must be imported');

    const relevantErrors = consoleErrors.filter((text) => !text.includes('Failed to load resource'));
    assert(relevantErrors.length === 0, `unexpected console errors: ${relevantErrors.join(' | ')}`);
  },

  /** The same archive after the server is restarted: everything durable is still there. */
  async 'after-restart'(browser) {
    const { page, consoleErrors } = await openAdmin(browser);
    assert((await collectionNames(page)).includes('Notes'), 'the collection must survive a restart');
    await selectCollection(page, 'Notes');
    for (const name of ['held-02.txt', 'held-03.txt']) await waitForRow(page, name);
    assert((await rowOf(page, 'held-02.txt')).status === 'Complete', 'a completed document must stay complete across a restart');
    assert((await rowOf(page, 'held-01.txt')) === null, 'a deleted document must not come back after a restart');
    const history = await importHistory(page);
    assert(history.length >= 1, 'the import history must survive a restart');
    assert(history[0].state === 'Complete', `the finished import must read Complete after a restart, got ${history[0].state}`);
    const perFile = await showFiles(page, 0);
    assert(perFile.some((item) => item.outcome === 'Cancelled'), 'the cancelled file disposition must survive a restart');
    assert((await pagingText(page)).includes('of 2 documents'), 'the document count must survive a restart');

    const relevantErrors = consoleErrors.filter((text) => !text.includes('Failed to load resource'));
    assert(relevantErrors.length === 0, `unexpected console errors: ${relevantErrors.join(' | ')}`);
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
      const panel = await activePage.textContent(PANEL);
      console.error(`panel text: ${JSON.stringify(panel)}`);
      await activePage.screenshot({ path: `/tmp/infoscry-collections-acceptance-${SCENARIO}.png`, fullPage: true });
      console.error(`screenshot: /tmp/infoscry-collections-acceptance-${SCENARIO}.png`);
    } catch (dumpError) {
      console.error(`could not dump page: ${dumpError}`);
    }
  }
  process.exitCode = 1;
} finally {
  await browser.close();
}
