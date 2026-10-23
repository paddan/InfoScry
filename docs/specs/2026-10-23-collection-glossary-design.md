# Collection glossary

Status: approved design, not implemented.

## Goal

A collection often names the same thing in several correct ways: *Sthlm* and *Stockholm*, an old and a new
name, a full name and its abbreviation. A person keeps a glossary per collection: groups of names with the
same meaning. Searching one name finds the others, and Ask and Investigate are told that the names mean the
same. Nothing in the text is changed; wrong text belongs to [collection corrections](2026-10-22-collection-corrections-design.md).

## Decisions

| Question | Decision |
|---|---|
| Shape of an entry | A group of equivalent terms, two-way: any term finds all the others |
| Word forms | Exact words and phrases; an optional per-term prefix flag matches words that start with the term |
| Semantic search | Unchanged. Only the keyword half of a search is expanded; hybrid fusion merges as today |
| LLM | Only the groups the request touches are sent, as a short block within the request budget |
| Construction | Query-time rewrite of the parsed Lucene query (approach 1 below). No reindexing, no job |

### Approaches considered

1. **Rewrite the parsed query (chosen).** The classic parser runs as today; the application walks the
   parsed query and adds the group's other terms as alternatives. No reindexing, a change applies from the
   next search, the prefix flag is a `PrefixQuery`, and the application knows exactly which terms it added,
   which highlighting and the *Also searched* line need.
2. **Lucene `SynonymGraphFilter` in a per-collection query analyzer.** Standard and good with phrases, but it
   cannot express the prefix flag, must be rebuilt on every change, and hides which alternatives were used.
3. **Index-time synonyms.** Every change would need a reindex; this contradicts a list that applies at once.

## Rules

### Storage

- `collection_glossary_groups (id TEXT PRIMARY KEY, collection_id, created_at, updated_at)`, deleted with
  its collection.
- `collection_glossary_terms (group_id, collection_id, ordinal, text, match_key, prefix)`, deleted with its
  group, `UNIQUE (collection_id, match_key)`.
- `text` is stored as typed, UTF-8 and normalized to NFC; it is what the UI shows and the LLM receives.
- `match_key` is the term run through the index's own analyzer (standard tokenizer, ICU folding, lower
  case), tokens joined by one space. *STHLM*, *sthlm* and *Sthlm* are one term; so are *Malmö* and *Malmo*,
  exactly as search already treats them.
- The first term is the group's display title only; it has no special role in matching.

### Validation

- A group has 2 to 20 terms. A term has at most 200 characters and at most 8 analyzed tokens, and must not
  analyze to nothing (punctuation alone is refused).
- Terms in one group with the same `match_key` are merged into one, keeping the first. A term whose key
  already belongs to another group in the collection is refused with 409 naming that group's id.
- The prefix flag is allowed only on a single-token term of at least 3 characters after analysis (the same
  floor as the lexical anchor); otherwise 400. It applies to that term only.
- Scripts without spaces are tokenized as search tokenizes them, so a term such as `東京` is a phrase of its
  tokens, matching how search treats the same text today.
- There is no limit on the number of groups; the bound is on how far one query is expanded.

### Operations

All are idempotent.

- `GET /api/collections/{id}/glossary` lists groups in creation order, terms in the order typed.
- `PUT /api/collections/{id}/glossary/{groupId}` with `{"terms":[{"text":…,"prefix":…}]}` creates or
  replaces the whole group. Repeating the same content changes nothing, not even `updated_at`. A group id
  owned by another collection answers 409.
- `DELETE /api/collections/{id}/glossary/{groupId}` answers 204, also when the group is already gone.
- The existing bearer and CSRF boundaries apply. One `GlossaryService` serves routes and CLI.
- A term that is also a correction's find text is allowed: the correction changes the text, the glossary
  affects only search and the LLM.

## Query expansion

Every keyword search goes through one seam in `SearchService`: the search box, Ask's retrieval and
Investigate's `search_collection` tool. The semantic half is untouched.

### Matching the query

- The query is parsed by `QueryParser` as today. The application then reads the analyzed tokens of runs of
  adjacent term clauses within one boolean level, and of each phrase.
- A query token sequence **hits** a group when it equals a term's `match_key`, or, for a prefix term, when
  the single query token starts with the term's key (*Sthlms* in the query hits `Sthlm*`).
- Longest match wins; matches do not overlap and do not chain (an alternative is never expanded again).

### Rewriting

- A hit is replaced by a disjunction of the original and the group's other terms, with the original's
  occurrence (SHOULD or MUST). A single-token term becomes a `TermQuery`, a multi-token term a
  `PhraseQuery`, a prefix term a `PrefixQuery` with constant-score rewrite. Alternatives weigh the same as
  the original.
- **Unquoted words** that together form a multi-token term (`Sthlms stad`): SHOULD clauses stay as they are
  and the alternatives are added as SHOULD clauses; MUST clauses are replaced by one MUST clause,
  (all the words) OR the alternatives.
- **Inside a quoted phrase** (`"hamnen i Sthlm"`) each alternative yields a variant phrase, the variants
  joined by OR, at most 16 variants per phrase. Prefix terms match exactly inside phrases.
- Wildcard, fuzzy, range and fielded clauses are left as they are.

### Bounds

- Expansion adds at most 256 clauses, and the whole query stays within `MAX_QUERY_TOKENS`. Groups that do
  not fit are skipped in the order they occur in the query, and the outcome says so. The glossary never
  causes a search to be refused.

### Across collections

A search without a collection filter keeps the original query everywhere and applies each collection's
expansion only within that collection:
`(Q AND NOT collection IN hit-collections) OR (collection = A AND E_A) OR …`, including only collections
whose glossary the query hits.

### Anchor and highlighting

- `hasLexicalAnchor` also probes the alternatives' tokens, so a semantic hit on *Stockholm* may be shown
  for a search on *Sthlm*.
- Single-token alternatives highlight as words; multi-token alternatives highlight only where the whole
  sequence occurs; prefix terms highlight words that start with the key.

### Outcome and freshness

- `SearchOutcome` gains `expandedWith` (the groups used, with their terms) and `expansionTruncated`. The
  search results show *Also searched: Stockholm, Sthlms stad* and, when truncated,
  *(some alternatives were left out)*.
- The glossary is cached per collection and the cache entry is dropped on every change, so a change applies
  from the next search.

## Ask and Investigate

### The block

Placed in the system message directly after the collection prompt, in English:

```
Glossary for this collection. Each line lists names with the same meaning; treat them as the same thing when reading the sources.
- Stockholm = Sthlm = Sthlms stad
- Kungliga Tekniska högskolan = KTH
```

- The glossary is the user's own configuration and trusted like the collection prompt; terms are still
  written only as data, with line breaks and control characters removed, so a term can never become an
  instruction line of its own.
- Investigate adds: *Keyword search already includes these alternatives; you do not need to search each
  name separately.*

### Which groups

- The same hit rule as query expansion (analyzed tokens, longest match, prefix).
- Groups the question hits are always sent; in Investigate this includes earlier questions in the
  conversation.
- Groups the sent evidence hits are added for the evidence actually sent.
- At most 40 groups and 1 500 tokens, counted with the budget's own token counter.

### Budget

- **Ask:** `ContextPacker` reserves the question's groups before choosing evidence. After the evidence is
  chosen, the evidence's groups are added in evidence rank order while they fit; a group that does not fit
  is left out. Evidence is never dropped to make room for the glossary.
- **Investigate:** the block is rebuilt before every LLM call from the messages left after pruning. It is
  part of the composed system prompt and not stored in the history. The question's groups belong to the
  part that is never pruned; evidence groups are left out first, and tool results are never pruned to make
  room for them.

### Traceability

- Groups sent are not stored with an answer. A saved answer keeps its citations and quoted text as today.
- Continuing an Investigate conversation uses the current glossary; a conversation locks its prompt, not the
  glossary. The same request with the same list yields the same block.

## User interface

- **Glossary** section in the collection's settings, below corrections, explained as *Names with the same
  meaning. Searching one finds the others; nothing in the text is changed.*
- Each group is a row of term chips, prefix terms marked, with **Edit** and **Remove**.
- **Add group** and **Edit** open a form: one text field per term with **Also match words that start with
  this**, add and remove term, **Save**. 400 and 409 answers show at the term concerned; a conflict names
  the group that holds the term. A new group's id is chosen when the form opens, so a retried Save cannot
  create a duplicate. Focus moves sensibly after **Remove** and when the form closes.
- The reader gains **Add to glossary** beside **Correct across collection**, opening the form with the
  selected word as the first term.
- Search results show the *Also searched* line described above.

## CLI

`infoscry collection glossary list|add|remove <collection>`, beside `collection corrections`, thin over
`GlossaryService`.

- `add <collection> <term>... [--prefix <term>]... [--group <id>]` creates a group, or replaces the group
  given by `--group`. Without `--group` it reuses the id of an existing group with exactly the same terms,
  so running it again changes nothing.
- `remove <collection> <group-id>` succeeds when the group is already gone.
- `list` prints tab-separated id and terms, prefix terms marked with `*`.
- Validation and conflict failures exit non-zero with the message on stderr.

## Errors and logging

- A failure reading the glossary never fails a search: the search runs unexpanded, the failure is logged
  without the terms, and `expandedWith` is empty. Ask and Investigate likewise send no block.
- No terms, queries or document text are written to logs.

## Schema

- Version 4: the baseline gains both glossary tables, marked `-- schema 4`; an upgrade step takes a
  version 3 database to version 4 in one transaction with foreign keys checked before it commits.

## Testing

- **Unit:** keys and validation; hit matching with longest match and prefix; query rewriting for SHOULD,
  MUST, unquoted multi-token terms, phrases, untouched clause kinds, the bounds and the cross-collection
  form; highlighting; the LLM block's selection, sanitizing and budget.
- **Store and routes:** idempotent `PUT` and `DELETE`, 409 for a term in another group and for a group id
  owned by another collection, cascade with the collection, migration from version 3.
- **Real Lucene index:** *Sthlm* finds *Stockholm* and the reverse; a prefix term finds *Sthlms*; another
  collection is unaffected; the lexical anchor accepts an alternative.
- **Ask and Investigate** with fake providers: the block is in the request actually sent, and the budget
  holds.
- **CLI:** `add` run twice gives one group.
- **Web:** Vitest for the section and the form.
- **Browser (`externalTest`, Playwright, `GlossaryBrowserAcceptanceTest`):** add a group, search, see
  *Also searched* and the hit, remove the group. Fake providers and embeddings.
- The semantic half is unchanged, so no new CoreML test; the existing `gpuIntegrationTest` still runs as the
  final gate.

## Out of scope

- Semantic expansion (embedding variants of the query).
- Stemming or language-aware inflection beyond the prefix flag.
- One-way aliases.
- Storing the glossary sent with saved answers.
