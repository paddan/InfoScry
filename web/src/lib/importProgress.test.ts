import { describe, expect, it } from 'vitest';
import { fileCountLabel, importProgressText, stageForReader, stageLabel } from './importProgress';

/**
 * The words one import is described in. The stage tokens are the ones the handlers write
 * (`ImportJobHandler`, `DocumentIngest`, `ReindexService`); everything around them is product copy.
 */
describe('stageLabel', () => {
  it('reads the stage tokens the job handlers write', () => {
    const tokens: Record<string, string> = {
      queue: 'Queued',
      copy: 'Copying',
      extract: 'Extracting',
      chunk: 'Building passages',
      embed: 'Embedding',
      index: 'Indexing',
      record: 'Saving results',
      rebuild: 'Rebuilding the index',
    };
    Object.entries(tokens).forEach(([token, label]) => {
      expect(stageLabel(token)).toBe(label);
    });
  });

  it('describes a token nobody wrote a label for instead of showing it raw', () => {
    expect(stageLabel('needs_tool')).toBe('Needs tool');
    expect(stageLabel('COPYING')).toBe('Copying');
    expect(stageLabel('waiting-on-provider')).toBe('Waiting on provider');
  });

  it('says nothing for a stage the job never reported', () => {
    expect(stageLabel(null)).toBe('');
    expect(stageLabel(undefined)).toBe('');
    expect(stageLabel('  ')).toBe('');
  });
});

describe('stageForReader', () => {
  it('shows the stage of a job that is still going', () => {
    expect(stageForReader('QUEUED', 'queue')).toBe('queue');
    expect(stageForReader('RUNNING', 'extract')).toBe('extract');
  });

  it('shows no stage for a finished job', () => {
    expect(stageForReader('COMPLETE', 'queue')).toBeNull();
    expect(stageForReader('COMPLETE', 'index')).toBeNull();
    expect(stageForReader('FAILED', 'record')).toBeNull();
    expect(stageForReader('CANCELLED', 'copy')).toBeNull();
  });

  it('says nothing for a job that never reported a stage', () => {
    expect(stageForReader('RUNNING', null)).toBeNull();
    expect(stageForReader('COMPLETE', undefined)).toBeNull();
  });
});

describe('importProgressText', () => {
  it('does not describe a finished import as queued', () => {
    const text = importProgressText({
      state: 'COMPLETE',
      stage: 'queue',
      currentItem: null,
      filesCompleted: 2,
      filesTotal: 2,
    });
    expect(text).toBe('Importing… — 2 of 2 files');
  });
});

describe('fileCountLabel', () => {
  it('counts files, and says how many of the import were finished when the total is known', () => {
    expect(fileCountLabel(3, 12)).toBe('3 of 12 files');
    expect(fileCountLabel(12, 12)).toBe('12 of 12 files');
  });

  it('counts what was finished when the job has not reported a total yet', () => {
    expect(fileCountLabel(1, 0)).toBe('1 file');
    expect(fileCountLabel(2, 0)).toBe('2 files');
  });
});

describe('importProgressText', () => {
  it('names the file being imported, the stage in words, and the file counters', () => {
    expect(importProgressText({
      state: 'RUNNING',
      stage: 'extract',
      currentItem: 'report.pdf',
      filesCompleted: 3,
      filesTotal: 12,
    })).toBe('Importing report.pdf — Extracting · 3 of 12 files');
  });

  it('says the attempt has not named a file yet, rather than leaving the file out silently', () => {
    expect(importProgressText({
      state: 'RUNNING',
      stage: 'copy',
      currentItem: null,
      filesCompleted: 0,
      filesTotal: 12,
    })).toBe('Importing… — Copying · 0 of 12 files');
  });

  it('leaves out a stage or a total the job has not reported', () => {
    expect(importProgressText({
      state: 'QUEUED',
      stage: null,
      currentItem: 'report.pdf',
      filesCompleted: 0,
      filesTotal: 0,
    })).toBe('Importing report.pdf');
    expect(importProgressText({
      state: 'RUNNING',
      stage: null,
      currentItem: null,
      filesCompleted: 0,
      filesTotal: 0,
    })).toBe('Importing…');
  });

  it('names no file once the import has finished: nothing is being read any more', () => {
    const finished = importProgressText({
      state: 'COMPLETE',
      stage: 'index',
      currentItem: 'done.pdf',
      filesCompleted: 12,
      filesTotal: 12,
    });
    expect(finished).toBe('Importing… — 12 of 12 files');
    expect(finished).not.toContain('done.pdf');
  });
});
