# 06 - Owner decisions carried over

## Decisions (2026-10-09)

The owner chose to retain the existing behaviour:

1. Extension filters apply both to individually chosen files and to files found in folders.
2. Ignore matching remains case-insensitive. `[abc]` character classes are not supported (brackets are literal); an individually chosen file is matched by its own name only.
3. Existing collections are not backfilled with default ignore patterns. The owner will reset the database; no existing-data migration or conversion is requested.
4. Page selection keeps the fixed 25% image-coverage and 75 text-quality thresholds. No settings are added.

## Model-capability claim

Removed the unsourced `imageInput: false` claim for `deepseek-chat` and
`deepseek-reasoner` from the curated catalog. Their capability is unknown unless
live provider metadata says otherwise; unknown is not proof of image support,
and the image-reading check is still required before an OCR method is usable.
The current [official API introduction](https://api-docs.deepseek.com/en/)
and [Vision guide](https://api-docs.deepseek.com/guides/vision/) describe image
input for `deepseek-flash`; neither supplies a current text-only capability
contract for the two legacy identifiers. Checked 2026-10-09.

Verification: `LlmModelCatalogTest` first failed the unknown-capability assertion
against the shipped false value, then passed after removing the claims. The
focused run also passed `PageCounterTest`. The accumulated gate is recorded in
[implementation status](../../implementation-status.md).
