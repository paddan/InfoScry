# 06 - Owner decisions carried over

Open questions from the removed local-testing-feedback record. They need the
owner, not code; each one changes behaviour.

1. Extension filter on individually chosen files: today the filter also applies to
   files chosen one by one, not only to files found in a folder. Keep?
2. `deepseek-chat` and `deepseek-reasoner` are marked text-only in
   `src/main/resources/.../providers.json` without a cited source. Add a source or
   remove the claim. A wrong "text-only" hides a model from OCR.
3. Ignore patterns: matching ignores case, `[abc]` classes are unsupported
   (brackets are literal), and a file chosen individually is matched by its own
   name only, so `node_modules/` does not skip it. Acceptable?
4. Collections created before ignore patterns existed start with an empty list, not
   the defaults. Backfill the defaults?
5. Page selection (new): is an image of at least 25% and a text score below 75 the
   right rule, or should the thresholds be settings?

Record each answer in this file and open a ticket for any that needs code.
