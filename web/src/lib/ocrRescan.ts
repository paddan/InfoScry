import type {
  OcrEngine,
  OcrImportMode,
  OcrLlmCandidate,
  OcrOperation,
  OcrOperationStage,
  OcrProfile,
  RescanPreviewOverrides,
  RetryOcrChoice,
} from './api';

/**
 * What a person picked in the shared OCR method controls. An empty string means "the collection's default",
 * which is also what an absent field means to the server, so a choice that names nothing changes nothing.
 */
export type OcrChoice = {
  engine: OcrEngine | '';
  mode: OcrImportMode | '';
  transcription: string;
  review: string;
  language: string;
};

export function emptyChoice(): OcrChoice {
  return { engine: '', mode: '', transcription: '', review: '', language: '' };
}

/** The fields of a choice that name something, as a rescan preview's overrides. */
export function rescanOverrides(choice: OcrChoice): RescanPreviewOverrides {
  const chosen: RescanPreviewOverrides = {};
  if (choice.engine !== '') chosen.engine = choice.engine;
  if (choice.mode !== '') chosen.importMode = choice.mode;
  if (choice.transcription !== '') chosen.transcriptionProfileId = choice.transcription;
  if (choice.review !== '') chosen.reviewProfileId = choice.review;
  return chosen;
}

/** The same choice for a retry, or undefined when it names nothing so the request stays as it always was. */
export function retryChoice(choice: OcrChoice): RetryOcrChoice | undefined {
  const chosen: RetryOcrChoice = { ...rescanOverrides(choice) };
  const language = choice.language.trim();
  if (language !== '') chosen.language = language;
  return Object.keys(chosen).length === 0 ? undefined : chosen;
}

/** The words a reader sees for each engine; the value sent to the server stays the enum. */
export const ENGINE_LABELS: Record<OcrEngine, string> = {
  TESSERACT: 'Tesseract (local)',
  SURYA: 'Surya (local)',
  LLM: 'Image model profile',
};

export const IMPORT_MODE_LABELS: Record<OcrImportMode, string> = {
  FILL_MISSING: 'Fill pages that have no text',
  CHECK_AND_IMPROVE: 'Check and improve existing text',
};

export const STAGE_LABELS: Record<OcrOperationStage, string> = {
  PREFLIGHT: 'Preparing',
  AWAITING_APPROVAL: 'Waiting for approval',
  OCR: 'Reading pages',
  REVIEW: 'Reviewing readings',
  CHUNKING: 'Building passages',
  EMBEDDING: 'Embedding',
  INDEXING: 'Indexing',
  COMPLETE: 'Complete',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
  NEEDS_TOOL: 'Needs a tool',
};

const TERMINAL_STAGES: OcrOperationStage[] = ['COMPLETE', 'FAILED', 'CANCELLED', 'NEEDS_TOOL'];

/** Whether nothing further happens to an operation without an explicit new attempt. */
export function isTerminalStage(stage: OcrOperationStage): boolean {
  return TERMINAL_STAGES.includes(stage);
}

/**
 * Whether an operation still owns its document: a working or waiting stage, or a complete one whose pages
 * still wait for a person. This is the server's own predicate, so Scan again is not offered into a refusal.
 */
export function holdsDocument(operation: OcrOperation): boolean {
  return !isTerminalStage(operation.stage) || (operation.stage === 'COMPLETE' && operation.pendingReviewCount > 0);
}

/** Decided pages waiting to be published; an older server that omits the count means none. */
export function decidedUnpublished(source: { decidedUnpublishedCount?: number | null }): number {
  return source.decidedUnpublishedCount ?? 0;
}

/** Whether the review step has something to do: pages to decide, or decided pages to publish. */
export function needsReviewStep(operation: OcrOperation): boolean {
  return operation.stage === 'COMPLETE' && (operation.pendingReviewCount > 0 || decidedUnpublished(operation) > 0);
}

/** One selectable profile in a reader's words: where it sends pages, its key and its image check. */
export function ocrProfileLabel(profile: OcrProfile): string {
  const where = profile.scope === 'EXTERNAL' ? 'external' : 'local';
  const key = profile.apiKeyEnvironmentVariable === null
    ? 'no key needed'
    : profile.keyAvailable ? 'key present' : 'key missing';
  const image = profile.imageCapabilityMeasured === true
    ? 'image check passed'
    : profile.imageCapabilityMeasured === false ? 'image check failed' : 'image check not run';
  return `${profile.name} (${where}, ${key}, ${image})`;
}

/**
 * One selectable LLM profile in a reader's words: where it sends pages, its key, and what the catalog states
 * about image input. An unstated model is called unknown, never image capable.
 */
export function llmCandidateLabel(candidate: OcrLlmCandidate): string {
  const where = candidate.scope === 'EXTERNAL' ? 'external' : 'local';
  const key = candidate.apiKeyEnvironmentVariable === null
    ? 'no key needed'
    : candidate.keyAvailable ? 'key present' : 'key missing';
  const image = candidate.imageInput === true ? 'image input listed' : 'image support unknown';
  return `${candidate.name} (${where}, ${key}, ${image})`;
}

/** A dollar amount with enough digits that a small estimate is not rounded into zero. */
export function formatUsd(amount: number): string {
  return `$${amount >= 0.01 ? amount.toFixed(2) : amount.toFixed(4)}`;
}

export function pageCount(count: number): string {
  return `${count} ${count === 1 ? 'page' : 'pages'}`;
}

/** A fresh idempotency key for one preview; reusing it for a repeated admission is what makes it one operation. */
export function newRequestId(): string {
  const crypto = globalThis.crypto as Crypto | undefined;
  if (crypto !== undefined && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}

/** Whether two snapshots are the same settings, which is what makes their approval hashes the same. */
export function sameSnapshot(left: object, right: object): boolean {
  const normalise = (value: object): string => JSON.stringify(
    Object.entries(value)
      .filter(([, entry]) => entry !== null && entry !== undefined)
      .sort(([a], [b]) => (a < b ? -1 : a > b ? 1 : 0)),
  );
  return normalise(left) === normalise(right);
}
