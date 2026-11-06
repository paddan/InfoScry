# Ticket 8: Uploads and import roots

Spec: [Multi-user server](../../specs/2026-11-05-multi-user-server-design.md), section *Import sources*;
ticket 8 of the suggested order, after [ticket 7](07-attribution-and-concurrent-use.md). Status: implemented
on branch `uploads` (2026-11-06), not yet merged to `main`.

## Goal

People import from their own machines by uploading, or from directories an administrator has opened on the
server. A path anywhere else on the server, also through a symbolic link, is refused, so editors can import
again without being administrators (ticket 6 limited server paths to administrators until now).

## Decisions

| Question | Decision |
|---|---|
| Roots | `INFOSCRY_IMPORT_ROOTS`, directories separated by `:`; each is resolved to its real path at start, and one that does not exist is logged and skipped |
| Which paths an import may name | A path whose real path (symbolic links resolved; for a path that is gone, its parent's) is inside a root, or inside the collection's own upload area. Checked in the preview, which the start also runs, so no import path bypasses it. The directory walk never follows links, as before |
| Browsing | `GET /api/import-roots` lists the roots; `GET /api/import-roots/browse?root=<index>&path=<relative>` lists one directory's folders and files. Only people who are an editor somewhere (or administrators) may browse. A link that leads outside its root is not listed |
| Upload | **Deviation from the spec's "multipart":** one file per request as the raw body, `POST /api/collections/{id}/uploads` with the file name in `X-InfoScry-Filename`. It is streamed to the collection's upload area and hashed on the way; one request per file, as the spec says, without a multipart parser |
| A repeated upload | Bytes that are already a document of the collection answer 200 with that document's id and store nothing; otherwise 201 with an upload id |
| Importing uploads | Preview and start take `uploads: [uploadId]` beside `paths`; the server resolves them to the upload area. The start dialog follows the upload as today |
| Upload area | `<data>/uploads/<collection>/<upload>/<file name>`; areas older than seven days are removed at start, since the import has its managed copy by then |
| Size | At most `INFOSCRY_UPLOAD_MAX_BYTES` (default 4 GiB) per file; a larger body is refused with 413 and nothing kept |
| Native picker | Stays administrator-only: it opens a dialog on the server's own screen |
| CLI | `infoscry import` uploads local files (walking a local folder, `--recursive` as before) and starts the import with them; `--server-path` names paths inside an import root instead |

## Tests

- Paths inside and outside a root, a symbolic link out of a root, a missing path in a root, the upload area of
  another collection.
- Browsing: listing, a link out of the root left out, `..` refused, a reader refused.
- Upload: a new file, the same bytes again before and after import, an over-size body, an editor of another
  collection refused; preview and import of an upload.
- CLI: an import of local files through upload, and `--server-path`.
- Web: uploading files into the import dialog, and browsing a root.

## Verification record (2026-11-06)

- `./gradlew check` with Podman: 2,248 backend and 588 frontend tests, 0 failures.
- `ImportSourcesRoutesTest`: a path inside a root accepted; a path outside, a link to a file outside, a link
  to a folder outside and a `..` path refused with `IMPORT_PATH_NOT_ALLOWED`, for an administrator too;
  browsing lists folders and files and leaves out a link out of the root, refuses `..` and readers; an
  upload previewed and imported under its own name, and the same bytes again answered with the document;
  readers, outsiders, an unnamed file and an over-size body refused with nothing kept; one collection's
  upload not importable into another; old upload areas swept. `ImportCommandProcessTest`: local files
  uploaded, a repeat reported as a duplicate without a new job, and `--server-path` outside the roots refused.
- Manual with the installed distribution and a ParadeDB container, `INFOSCRY_IMPORT_ROOTS` set to a
  directory with a folder and a link to a file outside it: an editor imported a local file with the CLI
  (and got a duplicate without a job the second time), imported a folder with `--server-path`, was refused
  the link, browsed the root without seeing the link, and found both documents by search; in the browser
  the editor's session survived a server restart, the upload button replaced the native picker, an upload
  showed its own file name in the start dialog, and the import completed.
- Found and fixed on the way: the start dialog showed `undefined pages` for a file whose page count is not
  known (an absent field, not `null`), and showed the server's upload path instead of the file name.
- Not run: `externalTest` and `gpuIntegrationTest`.
