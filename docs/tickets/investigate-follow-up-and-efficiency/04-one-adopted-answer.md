# 04: Keep one adopted answer in conversation history

**What to build:** A corrected Investigate response appears once as the final conversation answer, both immediately and after reopening. Later questions use that adopted answer while superseded output remains distinguishable audit data.

**Blocked by:** 03: Reuse retained evidence in follow-ups — correction must be verified with correctly restored historical evidence.

- [ ] Reproduce a successful citation correction that currently leaves original and corrected answers as ordinary conversation messages.
- [ ] Select and document a durable representation of adopted answers and superseded audit output. Any needed schema change preserves existing data and consumer contracts together.
- [ ] Each successfully completed new turn exposes one adopted final answer through the reader history and provider conversation history. Superseded output is excluded from those views but preserved as distinguishable audit data.
- [ ] A follow-up reusing historical evidence can undergo correction and still expose one answer with valid source references.
- [ ] Reloading and reopening the conversation displays the same adopted answer; a subsequent provider request contains the adopted answer and excludes its superseded draft.
- [ ] Successful and failed model calls remain auditable with their real usage and costs; separating drafts from conversation messages does not discard records or double-count calls.
- [ ] Establish an explicit legacy compatibility strategy. Adjacent assistant messages alone do not prove supersession; ambiguous old records are preserved without silent deletion or invented final-answer selection.
- [ ] A failed correction follows the existing fallback behavior, and cancelled or failed turns do not acquire a fabricated successful answer.
- [ ] Service, persistence, and route tests verify adopted history and retained audit behavior, including reopening and legacy ambiguity. Assertions target observable contracts rather than a particular unchosen schema.
- [ ] Browser acceptance with the local fake provider verifies one visible final answer after correction, after reopening, and before a further follow-up. Record migration/compatibility limitations and update affected documentation.
