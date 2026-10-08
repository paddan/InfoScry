import type { JobState } from './api';

/**
 * The words a person reads for one import, in one place.
 *
 * The server sends machine tokens — the stage a worker is in, the file it is holding — and every sentence
 * around them is product copy, so the translation lives here rather than in each panel. Nothing about an
 * import is decided here: an unknown token is described, never interpreted.
 */

/**
 * The stage tokens the job handlers write, as a reader reads them.
 *
 * `ImportJobHandler` and `DocumentIngest` report `queue`, `copy`, `record`, `extract`, `chunk`, `embed`
 * and `index`; a reindex reports `rebuild`. A stage a newer server adds is not in this table on purpose:
 * [stageLabel] describes it from the token itself instead of guessing what it does.
 */
const STAGE_LABELS: Record<string, string> = {
  queue: 'Queued',
  copy: 'Copying',
  extract: 'Extracting',
  chunk: 'Building passages',
  embed: 'Embedding',
  index: 'Indexing',
  record: 'Saving results',
  rebuild: 'Rebuilding the index',
};

/** The job states in which no file is being read, so a current file means nothing. */
const TERMINAL_JOB_STATES: JobState[] = ['COMPLETE', 'FAILED', 'CANCELLED'];

/**
 * The one stage a finished job keeps: the wait for a person's approval, which is why its attempt ended.
 * The server clears every other stage when a job ends; `JobStore.AWAITING_APPROVAL_STAGE` is its spelling.
 */
const AWAITING_APPROVAL_STAGE = 'awaiting-approval';

/**
 * The stage a reader is shown for a job, or `null` when the stage says nothing about the job now.
 *
 * A finished job is in no stage: the stage its last attempt entered (`queue`, `index`, ...) described work
 * that has stopped, so it is not shown. The approval wait is the exception, because it is why the attempt
 * ended. Rows written before the server cleared stages may still carry a stale one; this hides it.
 */
export function stageForReader(state: JobState, stage: string | null | undefined): string | null {
  if (stage === null || stage === undefined || stage.trim() === '') return null;
  if (TERMINAL_JOB_STATES.includes(state) && stage !== AWAITING_APPROVAL_STAGE) return null;
  return stage;
}

/**
 * The readable words for the stage a job reported: `extract` reads as `Extracting`.
 *
 * A token nobody wrote a label for is humanised rather than shown raw — `needs_tool` reads as
 * `Needs tool`, not as `NEEDS_TOOL` — because a stage the UI does not know is still something a person
 * is waiting on, and the server's vocabulary is not the reader's. An empty or missing stage is the empty
 * string, so a caller can leave it out of a sentence.
 */
export function stageLabel(stage: string | null | undefined): string {
  const token = (stage ?? '').trim().toLowerCase();
  if (token === '') return '';
  const known = STAGE_LABELS[token];
  if (known !== undefined) return known;
  const words = token.split(/[^a-z0-9]+/).filter((word) => word !== '');
  if (words.length === 0) return '';
  const [first, ...rest] = words;
  return [first.charAt(0).toUpperCase() + first.slice(1), ...rest].join(' ');
}

/** How many files an import has finished, in a reader's words: `3 of 12 files`. */
export function fileCountLabel(filesCompleted: number, filesTotal: number): string {
  if (filesTotal === 0) return `${filesCompleted} ${filesCompleted === 1 ? 'file' : 'files'}`;
  return `${filesCompleted} of ${filesTotal} files`;
}

/** What the server says about one import's progress; every field is absent when it has nothing to say. */
export type ImportProgress = {
  state: JobState;
  stage?: string | null;
  currentItem?: string | null;
  filesCompleted: number;
  filesTotal: number;
};

/**
 * The line a running import reads as: `Importing report.pdf — Copying · 3 of 12 files`.
 *
 * The file's own name comes from the server and is never the path it was selected from. Before the
 * attempt has named a file the line says `Importing…`, and a stage or a total the job has not reported
 * yet is left out rather than filled in with a zero. The current file is named only while the import is
 * unfinished: a finished import has no file being read, and its own sentence is the caller's to write.
 */
export function importProgressText(importing: ImportProgress): string {
  const current = TERMINAL_JOB_STATES.includes(importing.state) ? null : importing.currentItem;
  const headline = current ? `Importing ${current}` : 'Importing…';
  const parts = [stageLabel(stageForReader(importing.state, importing.stage))];
  if (importing.filesTotal > 0) parts.push(fileCountLabel(importing.filesCompleted, importing.filesTotal));
  const detail = parts.filter((part) => part !== '').join(' · ');
  return detail === '' ? headline : `${headline} — ${detail}`;
}
