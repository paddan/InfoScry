# 01: Collections replaces Import

**What to build:** The user manages imports through Admin → Collections rather
than a separate Import tab. They select or create a collection and add files or
a folder through the existing import flow, with a clear destination before
submission. This slice provides the usable collection workspace that later
tickets extend; it does not yet remove legacy Default.

**Blocked by:** None (can start immediately).

**Status:** done (2026-09-27) — verified by `./gradlew check` (all backend tests + frontend build),
`npx vitest run` (126 tests), and `svelte-check` (0 errors/warnings). Browser acceptance is
ticket 12.

- [x] Admin contains Collections and LLM profiles; the separate Import tab is
      removed without losing file/folder import behavior or profile management.
- [x] Collections lists active collections and document counts. Selecting a
      collection opens its management area without switching an active Ask or
      Investigate conversation to another collection.
- [x] An archive without collections shows `No collections yet` and
      `Create collection`; a collection without documents shows `No documents
      yet` and `Add documents`.
- [x] Collection creation validates names and case-insensitive uniqueness,
      presents server errors, selects the created collection in Admin, and
      refreshes the workspace collection selector.
- [x] The user can create a collection within the import flow. Every submitted
      import has an explicitly selected active collection; an empty or invalid
      selection cannot enqueue work.
- [x] `Add documents` supports native file selection, folder selection, optional
      subfolders, and the existing manual-path fallback when the picker cannot
      run in the server's graphical session.
- [x] Import remains server-owned and reports file results, including duplicates
      within the selected collection. No external original is modified.
- [x] Controls have English copy, accessible labels, restrained styling, visible
      pending/error states, and protection against repeated submission.
- [x] Route and component tests cover create/select/import, an empty archive,
      duplicate-name rejection, unavailable picker fallback, recursive choice,
      duplicate reporting, and isolation from conversation selection.
- [x] README describes the Collections entry point and current verification
      limits; meaningful failing tests, focused tests, and the accumulated
      offline check pass without unrelated dependency or extraction edits.
