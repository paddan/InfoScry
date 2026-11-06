/**
 * A browser context that is already signed in as the test server's administrator.
 *
 * The Kotlin launcher signs in over HTTP and hands the session cookie over in INFOSCRY_SESSION_COOKIE, so
 * the scripts exercise the workspace rather than the sign-in form (which has its own component tests).
 */
export async function signedInContext(browser, options = {}) {
  const base = process.env.BASE_URL;
  const cookie = process.env.INFOSCRY_SESSION_COOKIE;
  if (!base || !cookie) throw new Error('BASE_URL and INFOSCRY_SESSION_COOKIE are required');
  const context = await browser.newContext(options);
  await context.addCookies([{ name: 'infoscry_session', value: cookie, url: base, httpOnly: true, sameSite: 'Lax' }]);
  return context;
}
