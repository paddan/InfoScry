# Ticket 5: Accounts, sign-in and the CLI as an HTTP client

Spec: [Multi-user server](../../specs/2026-11-05-multi-user-server-design.md), sections *Architecture*
(listen address), *Users, sign-in and authorization* (accounts, sessions, personal access tokens) and
*Privacy and logging*; ticket 5 of the suggested order. Status: implemented on branch `auth-sessions` (2026-11-06), not yet merged to `main`.

## Goal

Every request to the API comes from a known user. A browser signs in with a username and password and
holds a server-side session; the CLI presents a personal access token. The per-launch bearer token,
`runtime.json` and the CLI's local mode go away: the server owns the database and every job, and the
CLI is a client of it. Administrators create and manage users from the web UI, the API or the
`infoscry admin` commands.

Memberships, per-collection roles and private conversations are ticket 6; attribution is ticket 7.
Until ticket 6 every signed-in user can use every collection, and only user management is limited to
administrators.

## Part A: accounts, sessions, tokens (server and web)

### Schema (in the baseline)

The tables are added to `001_baseline.sql`, which is edited in place as the migrator's contract says (there
are no archives to upgrade). Usernames are unique through the existing `infoscry_nocase` collation, as
collection names are, rather than `citext`; flags are 0/1 integers like every other flag.

| Table | Columns |
|---|---|
| `users` | `id`, `username` (unique without case), `display_name`, `password_hash`, `role` (`ADMIN`/`USER`), `active`, `must_change_password`, `created_at`, `created_by`, `updated_at`, `updated_by`, `last_sign_in_at` |
| `sessions` | `id_hash` (SHA-256 of the cookie value), `user_id`, `created_at`, `last_seen_at`, `expires_at`, `user_agent` |
| `access_tokens` | `id`, `user_id`, `name`, `token_hash` (SHA-256), `created_at`, `expires_at`, `last_used_at`, `revoked_at` |

`created_by` and `updated_by` are nullable here only for the first administrator, whom nobody created.

### Decisions

| Question | Decision |
|---|---|
| Password hash | Argon2id from Bouncy Castle (`bcprov-jdk18on` 1.86, pure Java), PHC string format, parameters pinned in code (m = 19 MiB, t = 2, p = 1, OWASP's baseline) and read back from the hash, so they can be raised later |
| Password rule | At least 12 characters, at most 1,024; nothing else |
| One-time passwords | 20 random characters from an unambiguous alphabet, shown once; `must_change_password` is set |
| Password change required | While set, only `GET /api/session`, `GET /api/me`, `POST /api/me/password` and sign-out answer; everything else is 403 `PASSWORD_CHANGE_REQUIRED` |
| Session ID | 32 random bytes, base64url, in cookie `infoscry_session` (`HttpOnly`, `SameSite=Lax`, `Path=/`, `Secure` when the public origin is HTTPS); only its SHA-256 is stored |
| Expiry | Idle 12 hours (`INFOSCRY_SESSION_IDLE_HOURS`), absolute 14 days (`INFOSCRY_SESSION_MAX_DAYS`); `last_seen_at` is written at most once a minute per session |
| CSRF | The session's CSRF token is derived from the session ID (SHA-256 of a fixed label and the ID), so it is bound to the session, needs no column and is worthless without the cookie. Required on every mutation authenticated by the cookie, in `X-InfoScry-Csrf` |
| Personal access tokens | `isc_` plus 32 random bytes in base64url; only the SHA-256 is stored; name required, expiry optional (days); revocation sets `revoked_at` and repeating it answers 204. Created and revoked from a browser session only, so a leaked token cannot mint more |
| Bearer requests | `Authorization: Bearer isc_…` authenticates as the token's user; no CSRF token is needed, since a browser never sends this header on its own |
| Unauthenticated | Every `/api/` route except `GET /api/session`, `POST /api/auth/sign-in` and `POST /api/auth/sign-out` answers 401 `AUTHENTICATION_REQUIRED`. The static frontend is served to anyone and shows the sign-in form |
| Sign-in failures | 401 `INVALID_CREDENTIALS` for an unknown user, a wrong password and a deactivated user alike; an unknown username still verifies against a dummy hash so timing does not tell them apart |
| Rate limit | In process (one server per database): 10 failures per username and 50 per client address in 15 minutes, then 429 `SIGN_IN_RATE_LIMITED` with `Retry-After`. A successful sign-in clears the username's count |
| Deactivation | Sets `active = false`, deletes the user's sessions and revokes their tokens in one transaction. Reactivation does not bring tokens back |
| Last administrator | Deactivating or demoting the last active administrator answers 409 `LAST_ADMINISTRATOR`; the check locks the administrator rows, so two concurrent demotions cannot both pass |
| Creating a user twice | Answers the existing user with `created: false` and no password, changing nothing |
| Listen address | `INFOSCRY_BIND` (`host:port`, default `127.0.0.1:8080`); `serve --port` still overrides the port. `INFOSCRY_PUBLIC_ORIGIN` (default `http://127.0.0.1:<port>`) names the origin browsers use |
| Host and Origin | The `Host` header must be a loopback name or the public origin's host. A mutation whose `Origin` header is present must be the public origin or a loopback origin on the bound port; otherwise 403 `CROSS_ORIGIN_REQUEST` |
| Logging | Usernames may be logged for sign-in and administration events; passwords, session IDs, CSRF tokens and access tokens never are |

### Routes

| Route | Who | Answer |
|---|---|---|
| `GET /api/session` | anyone | `{product, user?, csrfToken?}`; `user` and `csrfToken` only with a valid session |
| `POST /api/auth/sign-in` `{username, password}` | anyone | 200 `{user, csrfToken}` and the cookie |
| `POST /api/auth/sign-out` | anyone | 204; deletes the cookie's session if there is one |
| `GET /api/me`, `PATCH /api/me` `{displayName}` | signed in | the caller |
| `POST /api/me/password` `{currentPassword, newPassword}` | signed in | 204; ends the user's other sessions |
| `GET /api/me/tokens`, `POST /api/me/tokens` `{name, expiresInDays?}`, `DELETE /api/me/tokens/{id}` | browser session | the token value only in the create answer |
| `GET /api/users`, `POST /api/users` `{username, displayName?, role}` | ADMIN | the create answer carries the one-time password |
| `PATCH /api/users/{id}` `{displayName?, role?, active?}` | ADMIN | the user |
| `POST /api/users/{id}/password-reset` | ADMIN | `{oneTimePassword}`; ends the user's sessions |

### Admin commands

`infoscry admin create-user [--admin] [--display-name <name>] <username>`, `admin reset-password
<username>`, `admin list-users`. They connect to the database directly with the
`INFOSCRY_DATABASE_*` variables (applying the schema when it is new), so the first administrator can be
created before the server ever started, and an operator who lost every administrator password can get
back in. They take no process ownership: they change account rows only, which the running server reads
on every request.

### Web

A sign-in form when `/api/session` has no user; a change-password form while one is required; the
signed-in user's name and Sign out in the header; an Account section (display name, password, personal
access tokens); a Users tab under Admin for administrators (create, role, deactivate and reactivate,
reset password).

## Part B: the CLI as an HTTP client

- Every command except `serve`, `logs` and `admin` talks to the server at `INFOSCRY_URL` (default
  `http://127.0.0.1:8080`) with the token in `INFOSCRY_TOKEN`. Without a token the command fails before
  sending anything, saying how to create one.
- `CliSession.Local`, `runtime.json`, `RuntimeInfo`, `BearerToken` and `ApiCredentials` are removed.
  `import`, `search`, `reindex`, `ask` and `llm` no longer open the database; the standalone import
  that held process ownership goes away with them (the spec's rule "the CLI is a client; the server owns
  all jobs").
- Import paths are still paths on the server's file system; uploads and import roots are ticket 8.
- Only `serve`, `logs` and `admin` keep `--data-dir`; the other commands have no data directory.
- `import --json` and `reindex --json` lose `executedHere`, which can only be false now. A job's failure
  detail is the server log's, as it already was for server-run jobs.
- `llm add` loses `--tool-calling`, which declared nothing (tool calling is measured by `llm test`), and
  gains `--disabled`.

## Out of scope

Memberships and per-collection roles, private conversations (ticket 6), attribution columns other than
the ones `users` itself has (ticket 7), uploads and import roots (ticket 8), and the documentation
rewrite (ticket 9; this ticket keeps README and AGENTS.md accurate for what changed).

## Tests

- Failing first: routes answer 401 without a session; a mutation with the cookie but no CSRF token
  answers 403; sign-in sets the cookie.
- Idempotence: sign-in twice gives two sessions; sign-out twice answers 204; creating a user twice
  changes nothing; deactivating twice, revoking a token twice and resetting a password twice converge.
- Expiry (idle and absolute), deactivation ending sessions and tokens at once, the last-administrator
  rule including two concurrent demotions, the rate limit and its reset, the password-change gate, and
  tokens that are revoked or expired.
- The admin commands against a fresh database and against one a server is using.
- CLI commands against a running test server with a token.

## Verification record (2026-11-06)

- `./gradlew check` with Podman: 2,218 backend and 583 frontend tests, 0 failures.
- New tests: `AccountServiceTest` (idempotent create, sign-in refusals that read the same, two sessions and
  double sign-out, idle and absolute expiry, session-bound CSRF, deactivation ending sessions and tokens,
  the last administrator including two concurrent demotions, password reset and change, token expiry and
  revocation, both rate limits, Argon2id parameters in the hash), `AccountRoutesTest` (the same over the
  wire, the password-change gate, administrator-only routes, browser-only token management), a rewritten
  `SecurityTest` (unknown host, 401 without a caller, CSRF of another session, foreign `Origin`, cookie
  attributes, encoded and doubled API paths), and web tests for the sign-in gate and the account and user
  panels. Every CLI test now runs the CLI as a child process against a test server with a token.
- Found in review and fixed: the guard compared the raw path with `/api/`, but routing decodes segments, so
  `/%61pi/collections` answered 200 without a session. The new test failed against the old guard and passes
  with the decoded check.
- Manual, with the installed distribution, a ParadeDB container and the real CoreML model:
  `admin create-user --admin` before the first start (a repeat changed nothing), `serve`, 401 without a
  session, sign-in with the one-time password, `PASSWORD_CHANGE_REQUIRED` until it was changed, 403 for a
  mutation without CSRF, a token created in the session, then `collection create`, `import --wait` and
  keyword, semantic and hybrid `search` through the CLI with that token; in the browser: sign-in, the
  workspace, Admin → Users and sign-out.
- Not run: `externalTest` (the Playwright scripts now get the harness's session cookie) and
  `gpuIntegrationTest`.
