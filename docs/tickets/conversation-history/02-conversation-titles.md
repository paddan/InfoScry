# 02: Titles for new conversations

**What to build:** Every new Ask answer and every new Investigate conversation gets a short title
written from its opening question, so a reader recognizes a stored conversation by what it was
about instead of by its first 180 characters. Titling uses the conversation's own locked profile —
the same provider, endpoint and model that answered it — so no second model configuration is
needed. Both kinds of conversation are titled the same way, and the two lists the reader already
shows label their rows with the title, falling back to the truncated opening question when no title
exists.

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] A migration adds a nullable title column to conversations; existing rows stay unset, with no
      backfill and no re-titling.
- [ ] A new Ask answer and a new Investigate conversation each end with a title written from the
      opening question, and the title request goes to the conversation's own profile.
- [ ] A title is written once per conversation; continuing an Investigate conversation never
      rewrites it.
- [ ] Titling happens after the first turn has finished and been persisted, in the request path, so
      the title exists by the time the reader's next list read runs.
- [ ] A provider error, a malformed completion or an empty result leaves the title unset, leaves the
      conversation intact, and never surfaces an error to the reader or fails the answer.
- [ ] The title request is not recorded as a conversation model call, and its tokens are not folded
      into the conversation's reported cost.
- [ ] The title is length-capped before it is stored.
- [ ] The title prompt is a constant in code alongside the immutable core prompt rules — not a
      stored prompt override and not a new prompt role — and no new default profile role or
      dependency is introduced.
- [ ] Both conversation list endpoints return the title, and return the truncated opening question
      for a conversation that has none.
- [ ] The reader's existing question-history and conversation-history selectors label their rows with
      the title, falling back to the question.
- [ ] Backend route tests through the existing HTTP seam with the fake provider cover: a titled new
      Ask answer, a titled new Investigate conversation, the conversation's own profile being used,
      a titling failure leaving no title and not failing the answer, the list returning title and
      fallback, and a continued conversation not being re-titled.
