# 01: Source viewer as a sheet

**What to build:** A reader opens a citation — from a search result, an Ask answer or an Investigate
turn — and the source appears in a sheet that slides in over the history column instead of taking
the right-hand column of the content grid. The answer and its citations stay visible on the left the
whole time, so a claim and its source can be compared. The sheet is announced as a dialog, closes
with Escape, its close button, or a click outside it, moves focus into itself when it opens, returns
focus to the citation that opened it when it closes, and never covers the workspace sidebar.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] Opening a source no longer changes the content grid's column layout; the answer and its
      citations remain visible while the source is open.
- [ ] The source sheet is layered over the history column area, anchored to the right edge, and
      never covers the workspace sidebar.
- [ ] The sheet carries dialog semantics and is announced as a layer over the page rather than part
      of it.
- [ ] The sheet closes on Escape, on its close button, and on a click outside it.
- [ ] Focus moves into the sheet when it opens and returns to the citation that opened it when it
      closes.
- [ ] Everything the sheet already does keeps working: reading the source page, loading more text,
      reporting a failed read, and opening the original.
- [ ] A frontend test asserts the dialog role, the Escape close and the focus return to the opening
      citation.
- [ ] A manual browser pass checks the sheet's placement over the column, that it never covers the
      sidebar, focus under a real keyboard, and scrolling; the pass is reported as such, not as a
      test result.
