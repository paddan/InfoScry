import type { DocumentSort, DocumentStatusName } from './api';

export type DocumentListViewState = {
  expanded: boolean;
  filename: string;
  status: DocumentStatusName | '';
  sort: DocumentSort;
};

const statuses: string[] = [
  'QUEUED', 'COPYING', 'EXTRACTING', 'OCR', 'CHUNKING', 'EMBEDDING', 'INDEXING',
  'COMPLETE', 'COMPLETE_WITH_WARNINGS', 'FAILED', 'CANCELLED', 'NEEDS_TOOL',
];
const sorts: string[] = ['newest', 'oldest', 'name-asc', 'name-desc'];

/** Only view choices survive navigation; cached values are checked before reaching the API. */
export function restoreDocumentListViewState(state?: DocumentListViewState): DocumentListViewState {
  return {
    expanded: state?.expanded === true,
    filename: typeof state?.filename === 'string' ? state.filename : '',
    status: state && statuses.includes(state.status) ? state.status : '',
    sort: state && sorts.includes(state.sort) ? state.sort : 'newest',
  };
}
