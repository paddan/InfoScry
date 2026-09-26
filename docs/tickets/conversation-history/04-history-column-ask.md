# 04: History column in Ask, and a fresh answer marks its row

**What to build:** The same history column also serves the Ask view, listing the collection's stored
Ask questions and answers newest first under their titles. Clicking a row reopens that stored answer
with working citations, token counts and cost; `New conversation` clears the panel back to its
compose state, exactly as it behaves in Investigate. When a fresh answer finishes streaming, its own
row in the column becomes the marked one, so the column and the panel never disagree about which
answer is on screen. The panel's question-history dropdown goes away.

**Blocked by:** 03: History column in Investigate.

**Status:** ready-for-agent

- [ ] The Ask view reuses the same shared history column component; no second list implementation
      exists.
- [ ] The column lists only Ask questions and answers in the Ask view, with no Investigate
      conversations mixed in.
- [ ] Clicking a row shows that stored answer in the panel with its citations still openable, and
      with its token counts and cost.
- [ ] `New conversation` in the column header clears the Ask panel to its compose state.
- [ ] Asking a new question makes the conversation the server stored the marked row, so the column
      and the panel agree on what is being read.
- [ ] The Ask panel's question-history dropdown is removed; the panel still renders a stored answer
      from its own list object.
- [ ] The persisted Ask conversation reports the id it generated, and the Ask completion event
      carries that id, so the reader can mark the row of the answer it just streamed. The id is
      attached where the request path already has it, and the Ask service and its persistence seam
      keep their present shapes.
- [ ] Frontend tests through the mocked API module cover: clicking a row opening a stored answer,
      `New conversation` clearing the panel, and a streamed answer marking the row for the id carried
      in the completion event.
- [ ] A backend route test asserts the Ask completion event carries the conversation id and that the
      id matches the conversation that was stored.
