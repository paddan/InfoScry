# 03: History column in Investigate

**What to build:** A history column sits permanently to the right of the Investigate view. It lists
the selected collection's stored Investigate conversations newest first, each under its title, with
the currently open conversation marked. Clicking a row opens that conversation in the panel; a
`New conversation` button in the column header clears the panel to its empty compose state. The
column follows the collection selection, shows an empty state when the collection has nothing, and
the conversation last opened in a collection comes back after a reload — each collection and view
remembering its own, with nothing remembered starting empty. The panel's own conversation-history
dropdown goes away, and the column is absent in Search and Admin.

**Blocked by:** 02: Titles for new conversations.

**Status:** ready-for-agent

- [ ] One shared history column component renders the list, selected by the active view rather than
      duplicated per view.
- [ ] The reader surface owns the open conversation; the column and the panel read that one piece of
      state, so a row click and a `New conversation` move it once instead of in two copies.
- [ ] The column lists only the active view's kind of conversation — Investigate shows
      conversations, with no mixing in Ask questions.
- [ ] Rows are ordered newest first and capped at the fifty most recent conversations of the
      collection, matching the caps the list endpoints already apply.
- [ ] A row is a button holding the conversation's title, with a truncated-question fallback; the
      open row carries `aria-current` and a distinct background.
- [ ] The column only renders in the Investigate view and is hidden in Search and Admin.
- [ ] The column header holds the label and a `New conversation` button, which clears the panel to
      its empty compose state.
- [ ] The open conversation is remembered per collection and per view in browser storage, replacing
      Investigate's current single key; the automatic "open the newest conversation" fallback is
      removed, so a collection with nothing remembered starts empty.
- [ ] Switching collections shows that collection's remembered conversation, or the empty state, and
      never another collection's conversations.
- [ ] An empty conversation list shows the column's own empty state, distinct from a loading state.
- [ ] The panel's conversation-history dropdown is removed; the panel still shows a stored
      conversation's messages, evidence, activity, token counts and cost, and keeps
      continuing a conversation.
- [ ] Frontend tests through the mocked API module cover: the list rendered newest first with the
      open row marked, a row click opening the conversation, `New conversation` clearing the panel,
      the empty state, the column's absence in Search and Admin, and the remembered selection across
      a reload and a collection switch.
