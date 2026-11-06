# Ticket 6: Memberships, the authorization seam and private conversations

Spec: [Multi-user server](../../specs/2026-11-05-multi-user-server-design.md), section *Authorization*;
ticket 6 of the suggested order, after [ticket 5](05-accounts-and-sign-in.md). Status: implemented on
branch `memberships` (2026-11-06), not yet merged to `main`.

## Goal

A user sees only the collections they are a member of, and what they may do there follows their role.
Every route is decided in one place, and a route nobody classified fails a test. Ask conversations and
investigations belong to the user who started them.

## Roles

| Action | READER | EDITOR | OWNER | ADMIN |
|---|---|---|---|---|
| See the collection, search, read documents, Ask, Investigate | yes | yes | yes | yes |
| Import, rescan, retry, delete documents, review pages, cancel the collection's jobs | | yes | yes | yes |
| Edit corrections, glossary and collection prompts | | yes | yes | yes |
| Change collection settings (name, ignore patterns, OCR settings), manage members, delete the collection, reindex it | | | yes | yes |
| Create collections | any signed-in user; the creator becomes `OWNER` | | | |
| Manage users, LLM profiles, OCR profiles; reindex everything | | | | yes |

Administrators act as `OWNER` on every collection without a membership row.

## Decisions

| Question | Decision |
|---|---|
| Schema | `collection_members (collection_id, user_id, role, added_at, added_by)`, primary key `(collection_id, user_id)`, deleted with the collection; `conversations.owner_id`; `deletion_operations.requested_by` (in the baseline, as in ticket 5) |
| One place | `Authorization.kt` beside `Security.kt` holds a table from method and route pattern to a rule: public, signed in, administrator, a collection role taken from the path, a collection role taken from a job, or a collection role the handler checks on a collection named in the body. The request guard enforces every rule it can decide from the path; a handler that reads the collection from its body calls the same seam. A test walks Ktor's routing tree and fails for a route that has no rule |
| Non-members | A collection the caller is not a member of answers 404 with the same body as a collection that does not exist, so its existence is not revealed. A member whose role is too low gets 403 `COLLECTION_ROLE_REQUIRED` |
| Lists | The collection list, the job list and the unfinished-deletion list show only what the caller can see; administrators see everything |
| Membership changes | `GET /api/collections/{id}/members` (READER), `PUT /members/{userId}` `{role}` and `DELETE /members/{userId}` (OWNER). Setting the role a member already has, and removing someone who is not a member, change nothing. A collection always keeps one owner: removing or demoting the last one is 409 `LAST_OWNER`, decided with the owner rows locked |
| Choosing people | `GET /api/users/directory` lists active users' ids, usernames and display names to any signed-in user, so an owner can add members without the administrator list |
| Conversations | `owner_id` is set when an Ask or investigation is stored. Lists, reads, continuing, cancelling and deleting answer only for the owner; another member gets 404. Evidence links into documents need only collection access |
| Deletions | A deletion operation records who requested it; it is readable by that user and by administrators, also after the collection's rows are gone |
| Choosing server paths | Until ticket 8 brings import roots, an import's preview, start and the native file picker name paths on the server's file system, which any editor could otherwise use to read files the server can read. They require an administrator besides `EDITOR`; retries and rescans of documents already imported need only `EDITOR` |
| LLM tools | Investigations run on the collection the caller was authorized for; continuing one checks the owner and the membership again |
| CLI | Unchanged: the token carries the user, so a CLI command sees what its user sees |

## Out of scope

Attribution columns other than `added_by` and `requested_by`, optimistic edits (ticket 7), uploads and
import roots (ticket 8), sharing conversations.

## Tests

- Route by route against the table: a reader, an editor, an owner, a non-member and an administrator for
  each rule kind, including 404 (not 403) for non-members.
- The routing-tree test: every route has a rule.
- Memberships: add twice, remove twice, the last owner (including two concurrent demotions), the creator
  becoming owner, deletion of a collection removing its memberships.
- Conversations: another member cannot list, read, continue, cancel or delete them.
- Web: the members section of a collection's settings, and admin-only controls hidden from others.

## Verification record (2026-11-06)

- `./gradlew check` with Podman: 2,233 backend and 585 frontend tests, 0 failures.
- `AuthorizationTableTest` walks the routing tree: every API route has exactly one rule and every rule a
  route. `AuthorizationRoutesTest` covers each rule kind with a reader, an editor, an owner, an outsider and
  an administrator: the creator becoming owner, filtered collection and job lists, 404 for outsiders with the
  same body as a missing collection (by id and by name), 403 for too low a role, administrator-only
  profiles, body-named collections (Ask, search, reindex, import preview), jobs following their collection,
  private Ask conversations and investigations (list, read, continue, cancel, delete), idempotent
  membership changes, the last owner (also two concurrent demotions), following one's own collection
  deletion after the memberships are gone, and the user directory.
- Manual with the installed distribution, a ParadeDB container and the real CoreML model: two users signed
  in and made tokens; the second user saw no collections and got `NOT_FOUND` searching the first user's
  collection; after being added as reader, a hybrid search found the document, adding a glossary group was
  refused for the reader role, and renaming answered 403.
- Not run: `externalTest` and `gpuIntegrationTest`.
- The web interface shows members in a collection's settings (controls only for owners and administrators)
  and hides LLM profiles from non-administrators. Other owner-only controls (rename, ignore patterns,
  delete) are still shown to editors and readers, who get the server's refusal; hiding them is left for the
  UI work in ticket 7.
