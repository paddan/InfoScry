# InfoScry agent instructions

## Current repository

This repository is in the planning stage. Application code, build scripts, and
tests do not exist yet. Do not present planned paths or commands as implemented.

- Read [README.md](README.md) for current status.
- Read the [design specification](docs/superpowers/specs/2026-09-20-infoscry-design.md)
  for product requirements.
- Before implementation, read the [plan](docs/superpowers/plans/2026-09-20-infoscry-implementation.md):
  Global Constraints, Review Focus, the assigned task, and its prerequisites.

## Workflow

- Implement the requested task and its dependencies; do not expand into later
  phases without authorization. Resolve routine choices within the approved scope.
- If the design and plan conflict, report the exact contradiction rather than
  silently changing product behavior. Update affected contracts and consumers
  together when a change is authorized.
- Preserve unrelated working-tree changes. Keep commits scoped and follow the
  user's requested Git workflow.
- Use the plan's pinned dependencies. Verify a concrete incompatibility before
  changing the baseline and record the reason.
- For code changes, run the task's meaningful failing tests first, then focused
  passing tests and the applicable accumulated phase gate. Documentation-only
  changes need link, consistency, and diff checks rather than application tests.
- Report what was actually verified and what remains unverified. Never mark a
  task or hardware gate complete from mock results alone.
- Keep README status and commands current as implementation lands. Keep this
  file concise; put detailed procedures and version tables in the plan.

## Architecture and product rules

- Use Kotlin/JVM 25 for application services and TypeScript/SvelteKit for the
  static frontend. Keep HTTP routes and CLI commands thin over shared services.
- Use one application process and one Gradle backend module. SQLite owns
  authoritative state; Lucene and generated artifacts are rebuildable.
- Keep product copy in English and the UI restrained, accessible, and based on
  normal HTML/CSS. Follow the design's v1 scope.
- Never modify or delete external original source files. Managed copies are
  immutable; deduplicate by collection and SHA-256.
- Preserve stable content-unit IDs. Commit extraction results and checkpoints
  per unit, with durable artifact references, and resume without repeating
  committed OCR. Re-embedding must not invalidate extraction checkpoints.
- Apply shared mutation admission and exclusive maintenance as specified.
  Collection deletion must recover from persisted phases; an index swap must
  preserve all collections and publish its writer, reader, and embedder together.
- A standalone CLI import retains process ownership until completion. Returning
  after enqueue is valid only when a running server owns the job.

## Embeddings, LLMs, and local boundaries

- Require GPU embeddings: CUDA/O4 on Linux x86_64 and CoreML/standard ONNX on
  macOS arm64. Never silently substitute CPU-only inference. Preserve diagnostic,
  keyword-search, and source-viewing access when GPU initialization fails.
- Fit each complete embedding input within 512 tokens using the exact tokenizer,
  including prefix, special tokens, and repeated headers. Never truncate passages.
- Budget the full LLM request and reserved output, including history, tools, and
  citation metadata. Preserve tool-call/result groups when pruning context.
- Keep evidence IDs stable and validate citations against evidence actually sent
  in the generating request. Treat imported instructions as untrusted source data.
- Bind only to `127.0.0.1`; enforce the specified bearer/CSRF boundaries. LLM tools
  use collection-scoped opaque IDs, never arbitrary paths or web access.
- Read API keys only from environment variables. Keep keys, questions, answers,
  document text, and provider authorization headers out of logs and errors.
- Normal tests use redistributable fixtures, temporary data directories, and
  fake providers/tools. Do not send private documents to external services as
  test data. Real GPU tests on both target platforms must pass before release.
