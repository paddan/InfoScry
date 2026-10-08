/**
 * The reader-facing label for a text heuristic score (0-100, higher = cleaner-looking text). It judges only the
 * text's shape, not its accuracy. The label carries its level as text as well as a class, so the meaning never
 * depends on colour alone.
 */

export type OcrQualityLevel = 'good' | 'fair' | 'poor';

export type OcrQualityLabel = { text: string; level: OcrQualityLevel };

/** Null when the score is unknown; otherwise the score's text and its level band. */
export function qualityLabel(score?: number): OcrQualityLabel | null {
  if (score === undefined) return null;
  const level: OcrQualityLevel = score >= 75 ? 'good' : score >= 50 ? 'fair' : 'poor';
  return { text: `Text score ${score}/100`, level };
}
