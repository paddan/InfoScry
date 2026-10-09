import type { OcrEngine, OcrImportMode } from './api';

/** Legacy revisions keep their original method and mode labels in history. */
export const ENGINE_LABELS: Record<OcrEngine, string> = {
  TESSERACT: 'Tesseract (local)',
  SURYA: 'Surya (local)',
  LLM: 'Image model profile',
};

export const IMPORT_MODE_LABELS: Record<OcrImportMode, string> = {
  FILL_MISSING: 'Fill pages that have no text',
  CHECK_AND_IMPROVE: 'Check and improve existing text',
};

export function pageCount(count: number): string {
  return `${count} ${count === 1 ? 'page' : 'pages'}`;
}

/** A fresh idempotency key for a restore attempt. */
export function newRequestId(): string {
  const crypto = globalThis.crypto as Crypto | undefined;
  if (crypto !== undefined && typeof crypto.randomUUID === 'function') return crypto.randomUUID();
  return `req-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`;
}
