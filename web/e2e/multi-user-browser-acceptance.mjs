/**
 * The real-browser acceptance boundary for several people on one server: an administrator adds a user and
 * makes them an editor of a collection, the user signs in with the one-time password and chooses their own,
 * edits the collection's glossary, and the administrator sees the edit attributed to them. The editor also
 * chooses a file on their own computer to import.
 */
import assert from 'node:assert/strict';
import { chromium } from 'playwright';
import { signedInContext } from './session.mjs';

const base = process.env.BASE_URL;
assert(base, 'BASE_URL is required');
const clientFile = process.env.INFOSCRY_CLIENT_FILE;
assert(clientFile, 'INFOSCRY_CLIENT_FILE is required');
const browser = await chromium.launch({ headless: true, executablePath: process.env.INFOSCRY_CHROMIUM || undefined });

/** Opens the Default collection's settings and returns the settings section. */
async function openSettings(page) {
  await page.click('#tab-admin');
  await page.click('#admin-tab-collections');
  await page.locator('#admin-panel-collections .collection-list li button', { hasText: 'Default' }).click();
  const details = page.locator('details.secondary-settings:has(section[aria-label="Settings for Default"])');
  if (!(await details.evaluate((node) => node.open))) await details.locator(':scope > summary').click();
  return page.locator('section[aria-label="Settings for Default"]');
}

try {
  const errors = [];
  const admin = await (await signedInContext(browser)).newPage();
  await admin.setViewportSize({ width: 1600, height: 900 });
  admin.on('pageerror', (error) => errors.push(`admin: ${error}`));
  await admin.goto(base);

  // The administrator adds Erik and reads the one-time password, shown once.
  await admin.click('#tab-admin');
  await admin.click('#admin-tab-users');
  const addUser = admin.getByRole('form', { name: 'Add a user' });
  await addUser.getByLabel('Username').fill('erik');
  await addUser.getByLabel('Display name (optional)').fill('Erik');
  await addUser.getByRole('button', { name: 'Add user' }).click();
  const secret = admin.getByLabel('One-time password');
  await secret.waitFor({ timeout: 20_000 });
  const oneTimePassword = (await secret.textContent()).trim();
  assert(oneTimePassword.length >= 12, 'a one-time password is shown');
  console.log('PASS user-created');

  // ... and makes him an editor of Default.
  let settings = await openSettings(admin);
  const members = settings.getByRole('region', { name: 'Members' });
  await members.getByLabel('Add a person').selectOption({ label: 'Erik (erik)' });
  await members.getByLabel('Role', { exact: true }).selectOption('EDITOR');
  await members.getByRole('button', { name: 'Add member' }).click();
  await members.getByLabel('Role of Erik').waitFor({ timeout: 20_000 });
  assert.equal(await members.getByLabel('Role of Erik').inputValue(), 'EDITOR');
  console.log('PASS member-added');

  // Erik signs in in a browser of his own and has to choose a password first.
  const erikContext = await browser.newContext();
  const erik = await erikContext.newPage();
  await erik.setViewportSize({ width: 1600, height: 900 });
  erik.on('pageerror', (error) => errors.push(`erik: ${error}`));
  await erik.goto(base);
  await erik.getByLabel('Username').fill('erik');
  await erik.getByLabel('Password').fill(oneTimePassword);
  await erik.getByRole('button', { name: 'Sign in' }).click();
  await erik.getByRole('heading', { name: 'Choose your password' }).waitFor({ timeout: 20_000 });
  await erik.getByLabel('One-time password').fill(oneTimePassword);
  await erik.getByLabel('New password', { exact: true }).fill('erik-own-password-1');
  await erik.getByLabel('Repeat the new password').fill('erik-own-password-1');
  await erik.getByRole('button', { name: 'Change password' }).click();
  await erik.waitForFunction(() => document.querySelector('#collection option[value]') !== null, null, { timeout: 20_000 });
  console.log('PASS erik-signed-in');

  // An editor sees no administration of users or LLM profiles.
  await erik.click('#tab-admin');
  assert.equal(await erik.locator('#admin-tab-users').count(), 0, 'Users is hidden from Erik');
  assert.equal(await erik.locator('#admin-tab-llm').count(), 0, 'LLM profiles are hidden from Erik');

  // Erik adds a glossary group to the collection he edits.
  const erikSettings = await openSettings(erik);

  // To import, he chooses a file on his own computer; the server has no folders of its own to offer.
  await erik.getByRole('button', { name: 'Add documents' }).click();
  assert.equal(await erik.getByRole('button', { name: 'Server folders…' }).count(), 0, 'nothing is imported from the server');
  await erik.getByLabel('Files to upload').setInputFiles(clientFile);
  await erik.locator('.import-panel .paths li', { hasText: 'brev.txt' }).waitFor({ timeout: 20_000 });
  console.log('PASS editor-chose-file');

  const glossary = erikSettings.getByRole('region', { name: 'Glossary' });
  await glossary.getByRole('button', { name: 'Add group' }).waitFor({ timeout: 20_000 });
  await glossary.getByRole('button', { name: 'Add group' }).click();
  const dialog = erik.getByRole('dialog');
  await dialog.getByLabel('Term 1').fill('Stockholm');
  await dialog.getByLabel('Term 2').fill('Sthlm');
  await dialog.getByRole('button', { name: 'Save' }).click();
  await dialog.waitFor({ state: 'detached', timeout: 20_000 });
  await glossary.getByRole('listitem').filter({ hasText: 'Stockholm' }).waitFor({ timeout: 20_000 });
  console.log('PASS editor-edited');

  // The administrator, after reloading, sees the group and who made it.
  await admin.reload();
  settings = await openSettings(admin);
  const adminGlossary = settings.getByRole('region', { name: 'Glossary' });
  const group = adminGlossary.getByRole('listitem').filter({ hasText: 'Stockholm' });
  await group.waitFor({ timeout: 20_000 });
  await group.getByText(/Changed by Erik/).waitFor({ timeout: 20_000 });
  console.log('PASS attribution-shown');

  assert.deepEqual(errors, [], `no page errors: ${errors.join('\n')}`);
} finally {
  await browser.close();
}
