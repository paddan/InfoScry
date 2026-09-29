# Conversation history in the reader

## Problem Statement

The questions I ask and the answers I get are stored by the server, but I cannot
see them as a history. Ask hides its stored answers behind a dropdown labelled
"Question history", Investigate hides its conversations behind a dropdown
labelled "Conversation history", and neither dropdown shows me what a
conversation was about — it shows the raw first question, truncated. I cannot
delete a conversation I no longer want, so the only way to get rid of a stored
question is to delete the whole collection. And when I open a citation, the
source viewer takes over the right-hand column, so what I was reading disappears
from view.

## Solution

A history column sits permanently to the right of the Ask and Investigate views.
It lists the stored conversations for the selected collection — Ask questions and
their answers, Investigate conversations — newest first, each under a title the
model wrote from the opening question. I can open any of them by clicking its
title, start a new one from a button in the column header, and delete one with a
delete button on its row after confirming. The last conversation I had open for a
collection comes back when I reload or switch collections; with nothing
remembered, the view starts in an empty compose state.

Opening a citation shows a wide source viewer on the right. On a wide screen,
Search, Ask or Investigate stays visible in a narrower left reading area; the
viewer covers the history column while open. On a compact screen it covers the
content area but leaves the sidebar clear. It closes with Escape, a close button,
or by clicking outside it, returning focus to the citation that opened it.

## User Stories

1. As a reader, I want to see the conversation history beside the Ask view, so
   that I can reopen a stored answer without hunting through a dropdown.
2. As a reader, I want to see the conversation history beside the Investigate
   view, so that I can continue a conversation I started earlier.
3. As a reader, I want the history column to list only the active view's kind of
   conversation, so that Ask shows questions and answers and Investigate shows
   conversations, with no mixing.
4. As a reader, I want each history row to carry a title written from the opening
   question, so that I recognize a conversation by what it was about rather than
   by its first 180 characters.
5. As a reader, I want a conversation without a title (one stored before this
   change, or one whose title could not be written) to fall back to the truncated
   opening question, so that no row is ever blank.
6. As a reader, I want new conversations to be titled automatically, so that I
   never have to name anything.
7. As a reader, I want both Ask answers and Investigate conversations to be
   titled the same way, so that the column behaves identically in both views.
8. As a reader, I want the title to be generated only once, from the opening
   question, so that a long conversation does not get renamed under me as I
   continue it.
9. As a reader, I want titling to happen after my answer is finished, so that a
   slow or failing title call never delays or breaks the answer itself.
10. As a reader, I want a failed title call to leave the conversation usable with
    its question as the label, so that a provider hiccup never costs me the
    conversation.
11. As a reader, I want the newest conversation first, so that what I just did is
    at the top of the column.
12. As a reader, I want the column to show at most the most recent fifty
    conversations, so that a collection with years of questions stays fast.
13. As a reader, I want to click a row and see that conversation in the panel, so
    that reopening stored work is one click.
14. As a reader, I want the row I am currently reading to be marked, so that I
    know which conversation is on screen.
15. As a reader, I want a fresh Ask answer to mark its own row, so that the column
    and the panel never disagree about what I am looking at.
16. As a reader, I want a `New conversation` button in the column header, so that
    I can start a clean conversation without leaving the view.
17. As a reader, I want that button to clear the panel back to its compose state
    in Ask as well, so that the two views do not behave differently.
18. As a reader, I want to delete a single conversation from its row, so that I
    can discard one bad or unwanted conversation without touching the rest.
19. As a reader, I want to confirm the deletion before it happens, so that a
    misclick does not destroy a conversation that took a long time to build.
20. As a reader, I want the confirmation to name the conversation, so that I know
    exactly what I am about to lose.
21. As a reader, I want deletion to remove the whole conversation — its messages,
    model calls, citations, tool calls, evidence ledger entries and limit events —
    so that no orphaned rows accumulate and no source text lingers.
22. As a reader, I want deleting the conversation I am currently reading to clear
    the view to the empty state, so that I am not left looking at an answer the
    server no longer has.
23. As a reader, I want the delete button always visible on its row, so that I can
    reach it with a keyboard and on a touch screen, not only with a mouse hover.
24. As a reader, I want each delete button to name the conversation it deletes in
    its accessible label, so that a screen reader user hears what a button does
    before pressing it.
25. As a reader, I want the column to follow the collection I have selected, so
    that switching collections shows that collection's conversations and never
    another collection's.
26. As a reader, I want the conversation I last had open in a collection to come
    back after a reload, so that I can continue where I left off.
27. As a reader switching collections, I want each collection to remember its own
    open conversation, so that the two selections do not overwrite each other.
28. As a reader, I want to start in an empty compose state when a collection has
    nothing remembered, so that I am not dropped into an old conversation I did
    not ask for.
29. As a reader, I want an empty state in the column when a collection has no
    conversations yet, so that I can tell the difference between "nothing here"
    and "still loading".
30. As a reader, I want the history column hidden in the Search and Admin views,
    so that those views keep the width their tables and forms need.
31. As a reader, I want to open a citation while the history column is on screen
    and still see the answer and its citations, so that I can compare a claim with
    its source.
32. As a reader, I want the source sheet to stay out of the way of the sidebar, so
    that navigation is never blocked by it.
33. As a reader, I want to close the source sheet with Escape as well as its close
    button, so that reading a source and returning to the answer is quick.
34. As a reader, I want focus to move into the source sheet when it opens and back
    to the citation that opened it when it closes, so that I can keep working from
    the keyboard.
35. As a reader, I want the source sheet announced as a dialog, so that a screen
    reader user knows that it is a layer over the page rather than part of it.
36. As a reader, I want my citations to keep working from a reopened conversation,
    so that re-reading an old answer is as useful as reading a fresh one.
37. As a reader, I want a reopened conversation's token counts and cost, so that I
    can see what it cost me when I read it again.
38. As a reader, I want the titles to be written by the profile that answered the
    conversation, so that no second model configuration is needed for the feature
    to work.
39. As a reader, I want a title call not to appear as a conversation's model call,
    so that the cost the panel shows me is the cost of the answer.
40. As a reader, I want the existing stored conversations to keep working without a
    backfill job, so that upgrading costs nothing and touches nothing.
41. As an operator, I want to delete a conversation through the HTTP API scoped to
    its collection, so that one collection's conversations can never be deleted
    through another collection's key.
42. As an operator, I want a delete request for an unknown conversation to fail
    with a not-found error, so that a wrong id is reported instead of silently
    succeeding.
43. As an operator, I want a delete request that names a collection the
    conversation does not belong to to fail with a not-found error, so that the
    collection boundary is enforced by the server, not by the UI.
44. As a maintainer, I want the titling prompt to live in code with the other core
    prompt rules, so that it cannot be edited into a different behaviour from the
    database.
45. As a maintainer, I want no new prompt role, no new default-profile role and no
    new dependency, so that the feature adds behaviour without new configuration
    surface.

## Implementation Decisions

### Reader surface

- One shared history column component renders the list for both Ask and
  Investigate. It is selected by the active view rather than duplicated per view.
- The page owns the open conversation for each view. The column and the panel both
  read that selection, so a delete, a fresh answer and a row click all move one
  piece of state instead of two copies of it.
- The column renders only in the Ask and Investigate views and is hidden in Search
  and Admin.
- The column header holds the label and a `New conversation` button. In Ask, the
  button clears the panel to its compose state, matching the "no conversation
  selected" state of Investigate.
- A row is a button holding the title; the open row carries `aria-current` and a
  distinct background. A delete button sits on every row, always visible, with an
  accessible label naming the conversation it removes.
- Deletion goes through the platform's native confirmation dialog, showing the
  conversation title, before the request is sent. No custom modal is introduced.
- The open conversation is remembered per collection and per view in browser
  storage, replacing the Investigate view's current single key. With nothing
  remembered the view starts empty; the automatic "open the newest conversation"
  fallback is removed.
- Ordering is newest first and the list is capped at fifty conversations per
  collection, matching the caps the list endpoints already apply.
- Product copy is English, and the column uses ordinary HTML and CSS with the
  reader's existing dark theme.

### Titles

- The `conversations` table gains a nullable `title` column through a new
  migration. Existing rows stay `NULL`.
- Every list of conversations reads the title with a fallback to the truncated
  opening question, so a conversation without a title looks exactly as it does
  today. No backfill and no re-titling of existing conversations.
- A title is written once per conversation, from the opening question, for both
  Ask and Investigate. Continuing an Investigate conversation never rewrites it.
- The title request is a small non-streaming completion against the
  conversation's own profile — the same provider, endpoint and model it already
  locked in at creation. No new profile role and no new default.
- The title request is not recorded as a conversation model call and its tokens
  are not folded into the conversation's cost, so what the panel reports stays the
  cost of the answer.
- Titling happens after the first turn has finished and has been persisted, in the
  request path, so the title exists by the time the reader's list refresh runs.
  A failure, an error response or an empty result leaves the title unset and the
  row falls back to the question; it never surfaces an error to the reader and
  never fails the answer.
- The title prompt is a constant alongside the immutable core prompt rules. It is
  not a stored prompt override and not a new prompt role.
- Titles are length-capped before being stored, so a runaway completion cannot
  turn a row into a paragraph.

### Persistence and API

- `persistAsk` returns the conversation id it already generates, so the request
  path can act on the conversation it just created.
- The Ask completion event carries that conversation id, so the reader can mark
  the row belonging to the answer it just streamed. The id is attached by the
  route from what it already captured for titling; the Ask service and its
  persistence seam keep their present shapes.
- One delete route serves both views:
  `DELETE /api/collections/{collectionId}/conversations/{conversationId}`. It
  resolves the collection by id or name exactly as the other collection-scoped
  routes do, verifies that the conversation exists in that collection, and
  responds not-found for an unknown id or a conversation belonging to another
  collection. It goes through the reader's existing CSRF-bearing mutation helper.
- The store gains a single deletion operation keyed by collection and
  conversation. The schema's existing cascade removes messages, model calls,
  citations, tool calls, evidence ledger entries, request eligibility, request
  omissions and limit events with the conversation row.
- A shared titling helper takes the conversation id, the opening question and the
  profile, builds its own client the way the routes already build one, and
  persists the result. Both request paths call it.

### Source viewer

- The source viewer is a wide fixed sheet anchored to the right edge. On wide
  screens, the active Search, Ask or Investigate panel uses the remaining left
  area; the history column sits behind the viewer. On compact screens, the
  viewer covers the content area while leaving the sidebar uncovered.
- The sheet carries dialog semantics, is announced as modal, and closes on
  Escape, on its close button, and on a click outside it.
- Focus moves into the sheet when it opens and returns to the citation that opened
  it when it closes.
- The sheet is sized so it never covers the workspace sidebar except on mobile,
  where the sidebar is above the content and the viewer fills the viewport.

## Testing Decisions

A good test here asserts externally visible behaviour: the HTTP request and
response for the server, and what the reader sees and can do for the interface.
Tests do not assert internal call sequences, private helpers, or the shape of
intermediate state, and they never assert against a real provider, a real
document, or a real model.

**Backend seam — the HTTP route boundary against real SQLite and the fake
OpenAI-compatible provider.** This is the highest existing seam and needs nothing
new: the titling helper builds its client the same way the routes do, so the fake
provider already sits in front of it. Prior art is the existing Ask and
Investigate route tests with their test server and fake provider. Through this
seam the tests cover:

- A new Ask answer and a new Investigate conversation each end with a title
  written from the opening question, and the title comes from the conversation's
  own profile.
- A titling failure (provider error, malformed or empty completion) leaves the
  conversation intact, stores no title, and does not fail the answer.
- The conversation list returns the title, and returns the truncated opening
  question for a conversation that has none.
- Continuing an Investigate conversation does not rewrite its title.
- The Ask completion event carries the conversation id, and the id matches the
  conversation that was stored.
- Deleting an existing conversation in its own collection succeeds, and the
  conversation and its messages, model calls, citations, tool calls, evidence
  ledger entries and limit events are gone afterwards.
- Deleting an unknown id, and deleting a conversation through another
  collection's id, both answer not-found and remove nothing.
- A delete request without the mutation credential is rejected.

**Frontend seam — the `api` module boundary, driven through the page component.**
Prior art is the existing page test plus the panel tests, which mock the `api`
module and assert rendered output and interaction. Through this seam the tests
cover:

- The column lists the conversations the API returned, newest first, with the open
  row marked.
- Clicking a row opens that conversation in the panel; the `New conversation`
  button clears the panel to its compose state.
- Deleting a row asks for confirmation, calls the API only when confirmed, does
  nothing when declined, and refreshes the list afterwards.
- Deleting the open conversation clears the panel to the empty state.
- A fresh Ask answer marks the row for the conversation id carried in the
  completion event.
- The column is absent in the Search and Admin views.
- An empty list shows the column's empty state.
- The source viewer is announced as a dialog, closes on Escape, and returns focus
  to the citation that opened it.
- The panel tests drop their assertions on the removed history dropdowns and keep
  their coverage of a stored answer and a stored conversation.

**Browser and manual checks.** Chromium acceptance measures the Search panel and
source preview at wide and compact viewport widths. DOM tests cover source
opening from Search, Ask and Investigate. Human keyboard, screen-reader and
visual checks of the complete source viewer remain open.

**Gate.** The JVM suite and the frontend suite run together through the project's
`check` task, which already drives the frontend tests; the frontend type and
template diagnostics run separately with the web project's `check` script.

## Out of Scope

- Clearing all conversations for a collection, and bulk deletion of any kind.
- Soft deletion, undo, or any recovery path after a confirmed deletion.
- A filter or search field inside the history column.
- Per-row metadata badges such as cost, token counts or turn counts.
- Backfilling titles for conversations stored before this change.
- Re-titling or renaming a conversation after its first turn, and any
  reader-editable title.
- A dedicated title prompt role, a title default profile, or a stored prompt
  override for the title prompt.
- Scripted end-to-end browser tests.
- Changes to the Ask or Investigate retrieval, budgeting or citation behaviour.

## Further Notes

- The two test seams above were proposed during the design interview and were not
  objected to; if you would rather test the backend at the service layer with
  fakes, that is the one decision here worth revisiting before implementation.
- Assumptions the design did not put to the reader: titles are capped at roughly
  eighty characters; a list read that fails stays silent, as the panels behave
  today; switching collections shows that collection's remembered conversation or
  the empty state; the column keeps the existing fifty-row cap; product copy is
  English.
- There is no configured issue tracker or triage vocabulary for this project, and
  the setup skill the workflow expects is not installed, so this spec lives in the
  repository instead of being published with a readiness label. The `docs/`
  directory was removed earlier in the project's history ("Removed specs"); the
  project instructions still reference documents under it, which no longer exist
  and should be corrected or dropped.
