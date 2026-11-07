# Image tagging

Status: design approved 2026-11-07; not implemented.

Sub-project 2 of the image work. Sub-project 1, the [image foundation](2026-11-07-image-foundation-design.md),
finds, stores, shows and cites images; this spec makes what an image *shows* searchable and citable. Sub-project 3
(faces grouped per collection and named by people) stays out of scope.

## Goal

A person asks "show pictures of Jeffrey", "photos with a horse", "the church on the cover" and gets the pictures.
An image-capable LLM chosen per run describes each image and tags the people, objects, animals, places and visible
text in it — generic kinds always, specific names when the text around the picture says them, and, when the person
asks for it, specific names the model recognises from appearance, marked as uncertain.

## Decisions

| Question | Decision |
|---|---|
| Who analyses | An LLM profile chosen in the start dialog (Ollama, Claude, OpenAI, OpenRouter, any OpenAI-compatible endpoint); the profile must pass the existing image-capability check |
| Language | The collection's language (its OCR language), for description, kinds and names |
| Names | From the caption and page text (source `TEXT`), and — only with the run's *Identify from appearance* choice — from appearance (source `APPEARANCE`, shown as "possibly …"); applies to every category |
| Editing | Editors confirm, remove and add tags per image; edits survive later analyses and carry attribution |
| Where it runs | A step after the image step of an import or rescan (approach A below); also offered by *Find images in all documents…* |
| Search | Description and tags are part of the image unit's indexed text; a category filter restricts hits to pictures |

### Approaches considered

1. **A step in the image pipeline (chosen).** The analysis becomes part of the image unit's indexed text and is
   published in a new document revision: one transaction, one search store, history and Restore for free.
2. **Tags outside revisions, search rows updated in place.** Faster edits, but it breaks "a reading and its searchable
   rows commit in one transaction" and loses history.
3. **A separate "Describe images" job.** Simple to explain, but it duplicates run machinery and an import that redoes
   the image step would not tag.

## Rules

### Tags

- A tag is `{category, label, name?, source}`:
  - `category`: `PERSON`, `OBJECT`, `ANIMAL`, `PLACE` or `TEXT`;
  - `label`: the generic kind ("man", "car", "dog", "church");
  - `name`: the specific identity when known ("Jeffrey Epstein", "Volvo PV444", "Labrador", "Uppsala Cathedral",
    "Mona Lisa"), else absent;
  - `source`: `TEXT` when the name comes from the caption or page text, `APPEARANCE` when the model recognised it from
    the picture, `MANUAL` for a tag a person added or confirmed.
- A `TEXT` or `MANUAL` name is shown as stated; an `APPEARANCE` name is shown and indexed as "possibly <name>".
- At most 30 tags and a description of at most 400 characters per image.

### Storage

- `image_analyses`: one row per `(document_id, unit_id, image_sha256, profile_revision_id, prompt_version, language,
  identify_appearance)` with `outcome` (`DONE`, `FAILED`), `description`, `tags` (JSON), `error_code`, `created_at`.
  A `DONE` row is reused whenever the same image is analysed with the same inputs: no model call.
- `image_tag_edits`: `(document_id, unit_id, edit_id)` with `action` (`ADD`, `REMOVE`, `CONFIRM`), the tag, `created_by`,
  `created_at`. Edits apply on top of every analysis: a removed tag stays removed, a confirmed tag becomes `MANUAL`.
- `collections.default_image_profile_id` (nullable) and `collections.default_identify_appearance` (0/1), changed with
  the collection's version (409 `EDIT_CONFLICT` when stale).
- `search_chunks.image_categories` (text array, empty for text units), written in the publication transaction.
- The image unit's indexed text is: caption, blank line, description, blank line, tags as `label: name` lines
  (`possibly name` for `APPEARANCE`), blank line, page context. The chunker splits it; nothing is truncated.

### The model call

- Input: the image (longest side at most 1,568 px), its caption, its page context (at most 600 characters), the
  collection's language and whether to identify from appearance. Captions and page text are untrusted data in the
  prompt.
- Output: strict JSON `{description, tags[]}`, validated against the rules above. An invalid answer is
  `IMAGE_ANALYSIS_INVALID`, a refusal `IMAGE_ANALYSIS_REFUSED`, a timeout `IMAGE_ANALYSIS_TIMEOUT` — for that image only.
- Without *Identify from appearance* the prompt tells the model to name only what the text states; with it, to also
  name what it recognises and say so. A provider that refuses to identify people still yields generic person tags.

### Runs

- The import and rescan start dialogs gain *Describe images with* (None or a profile that passed the image check) and
  *Identify specific people, objects, animals and places from their appearance* (off by default); the collection
  defaults pre-fill them. *Find images in all documents…* offers the same two choices; *Keep current text* with a
  profile describes images without reading any page.
- The choices are frozen in the attempt snapshot. The analysis runs per image unit after the image step, each result
  committed on its own; a resumed or repeated run reuses `DONE` analyses and retries `FAILED` ones.
- External sending is approved once in the dialog: the number of images, the provider and a cost estimate are shown,
  and the run may send at most that many images, as LLM OCR does. Nothing is sent again mid-run.
- A failed image never fails the document; the document is complete with a warning that names the code.
- An edit publishes a new revision of the document carrying the edited image unit's text; it is versioned like other
  shared edits.

### Search, Ask and the reader

- Search: the filter *Pictures: All | People | Objects | Animals | Places* restricts hits to image units with a tag of
  that category (`image_categories`); it combines with keyword, semantic and hybrid search.
- Ask and Investigate: an image hit is sent as evidence labelled `Picture: image 2 on page 14`, with its description,
  its tags and their sources (*from the text*, *possibly, from appearance*) and its caption; the prompt says uncertain
  names must be stated as uncertain. A citation opens the picture.
- The image viewer shows the description and tags grouped by category; `APPEARANCE` tags read "possibly …" with
  **Confirm** and **Remove**; editors can **Add tag** (category, kind, name). Readers see tags but cannot change them.
  Each edit shows who made it.
- Collection settings gain *Describe images with* and *Identify from appearance*.
- CLI: `infoscry import … --describe-images <profileId|none> [--identify-appearance]`.

## Errors and logging

No image bytes, prompts, answers, captions or tags in logs or errors. New codes: `IMAGE_ANALYSIS_INVALID`,
`IMAGE_ANALYSIS_REFUSED`, `IMAGE_ANALYSIS_TIMEOUT`, `IMAGE_PROFILE_UNAVAILABLE`.

## Schema

`007_image_tagging.sql` (version 7): `image_analyses`, `image_tag_edits`, the two collection columns and
`search_chunks.image_categories`; existing rows are kept, text units get an empty category list.

## Testing

- A fake image LLM with scripted JSON answers; no real provider in normal tests.
- Validation (invalid JSON, too many tags, unknown category), reuse with zero calls on repeat and after interruption,
  per-image failure, `TEXT` versus `APPEARANCE` sources, edits surviving a new analysis, the category filter, Ask
  evidence labels and uncertainty, external sending approval and its cap, authorization (editor edits, reader reads,
  non-member 404).
- Browser acceptance: import with a fake profile, a tag in the viewer, confirm a suggestion, a search with *People*,
  an Ask citation opening the picture.
- Real check before reporting it working: one run against a local Ollama vision model on a redistributable test
  picture. No private documents are sent to external services as test data.

## Out of scope

Face grouping and names spread across pictures (sub-project 3), video and SVG, translating earlier analyses when the
collection's language changes (a new run does it).
