# 05: Delete a conversation from its row

**What to build:** A reader discards one conversation without touching the rest of the collection or
the collection itself. Every history row carries an always-visible delete button whose accessible
label names the conversation it removes; pressing it asks for confirmation through the platform's
native dialog, showing the conversation's title, and only a confirmed answer sends the request. The
whole conversation goes — messages, model calls, citations, tool calls, evidence ledger entries and
limit events — so nothing orphaned and no source text lingers. Deleting the conversation currently
being read clears the view to its empty state, and the list refreshes afterwards.

**Blocked by:** 03: History column in Investigate; 04: History column in Ask, and a fresh answer
marks its row.

**Status:** ready-for-agent

- [ ] One delete route serves both views:
      `DELETE /api/collections/{collectionId}/conversations/{conversationId}`. It resolves the
      collection by id or name exactly as the other collection-scoped routes do, verifies that the
      conversation exists in that collection, and answers not-found for an unknown id or for a
      conversation that belongs to another collection. It goes through the reader's existing
      CSRF-bearing mutation helper.
- [ ] The store gains a single deletion operation keyed by collection and conversation; the schema's
      existing cascade removes the conversation's messages, model calls, citations, tool calls,
      evidence ledger entries and limit events with the conversation row.
- [ ] Deleting an existing conversation in its own collection succeeds, and afterwards the
      conversation and all of those children are gone.
- [ ] Deleting an unknown id, and deleting a conversation through another collection's id, both
      answer not-found and remove nothing.
- [ ] A delete request without the mutation credential is rejected.
- [ ] The deleted conversation disappears from the column's list after the request succeeds, and the
      list is refreshed, not patched by hand.
- [ ] The delete button is always visible on its row — reachable with a keyboard and on a touch
      screen, not only on mouse hover — and its accessible label names the conversation it deletes.
- [ ] Deletion asks the platform's native confirmation dialog and names the conversation there; no
      custom modal is introduced, and the request is sent only when the reader confirms.
- [ ] Declining the confirmation calls the API not at all.
- [ ] Deleting the conversation currently being read clears the panel to the empty state and forgets
      the remembered selection for that collection and view.
- [ ] Backend route tests through the existing HTTP seam cover the successful delete, both not-found
      cases, and the rejected uncredentialed request.
- [ ] Frontend tests through the mocked API module cover: confirmation asked for before the call,
      the call made only when confirmed, nothing happening when declined, and a refresh afterwards.
