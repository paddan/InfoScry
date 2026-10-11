import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { ApiError, type PdfArtifactStatus } from './api';

const mocks = vi.hoisted(() => ({ status: vi.fn(), create: vi.fn() }));
vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  getSelectablePdfStatus: mocks.status,
  createSelectablePdf: mocks.create,
}));
import SelectablePdfActions from './SelectablePdfActions.svelte';

const KEY = 'f'.repeat(64);

const status = (over: Partial<PdfArtifactStatus> = {}): PdfArtifactStatus => ({
  revisionId: 'rev-1', key: KEY, state: 'READY', selectablePages: 3, totalPages: 3, blankPages: 0,
  unavailablePages: [], reasonCode: null, ...over,
});
const notCreated = (): PdfArtifactStatus => status({ key: null, state: 'NOT_CREATED', selectablePages: 0, totalPages: null });

/** A promise whose settlement a test controls, for answers that arrive late. */
function deferred<T>(): { promise: Promise<T>; resolve: (value: T) => void; reject: (error: unknown) => void } {
  let resolve!: (value: T) => void;
  let reject!: (error: unknown) => void;
  const promise = new Promise<T>((done, fail) => { resolve = done; reject = fail; });
  return { promise, resolve, reject };
}

const props = (over: Record<string, unknown> = {}) => ({
  collectionId: 'Archive', documentId: 'doc-1', revisionId: 'rev-1', pollMillis: 5, ...over,
});

beforeEach(() => {
  mocks.status.mockReset();
  mocks.create.mockReset();
  mocks.status.mockResolvedValue(status());
  mocks.create.mockResolvedValue(status({ state: 'QUEUED', totalPages: null, selectablePages: 0 }));
});
afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
  Object.defineProperty(document, 'hidden', { value: false, configurable: true });
});

describe('SelectablePdfActions identity and dispatch', () => {
  it('shows the source as unavailable and dispatches nothing when the revision is missing', async () => {
    render(SelectablePdfActions, { props: props({ revisionId: null }) });

    expect(await screen.findByText('Selectable PDF is unavailable for this source.')).toBeTruthy();
    expect(mocks.status).not.toHaveBeenCalled();
    expect(mocks.create).not.toHaveBeenCalled();
    expect(screen.queryByRole('button')).toBeNull();
  });

  it('treats an empty revision the same as a missing one', async () => {
    render(SelectablePdfActions, { props: props({ revisionId: '' }) });

    expect(await screen.findByText('Selectable PDF is unavailable for this source.')).toBeTruthy();
    expect(mocks.status).not.toHaveBeenCalled();
    expect(mocks.create).not.toHaveBeenCalled();
  });

  it('reads the status of its own revision on open and never creates a PDF just by being shown', async () => {
    render(SelectablePdfActions, { props: props() });

    expect(await screen.findByRole('link', { name: 'Open PDF with selectable text' })).toBeTruthy();
    expect(mocks.status).toHaveBeenCalledWith('Archive', 'doc-1', 'rev-1');
    expect(mocks.create).not.toHaveBeenCalled();
  });

  it('does not dispatch while the component is hidden and reads the status once it is shown', async () => {
    const view = render(SelectablePdfActions, { props: props({ visible: false }) });
    await new Promise((resolve) => setTimeout(resolve, 15));
    expect(mocks.status).not.toHaveBeenCalled();

    await view.rerender({ visible: true });
    expect(await screen.findByRole('link', { name: 'Open PDF with selectable text' })).toBeTruthy();
    expect(mocks.create).not.toHaveBeenCalled();
  });
});

describe('SelectablePdfActions per-state UI', () => {
  it('offers Create for a NOT_CREATED revision and posts with retry false only when clicked', async () => {
    mocks.status.mockResolvedValue(notCreated());
    render(SelectablePdfActions, { props: props() });

    const create = await screen.findByRole('button', { name: 'Create PDF with selectable text' });
    expect(mocks.create).not.toHaveBeenCalled();
    await fireEvent.click(create);

    expect(mocks.create).toHaveBeenCalledWith('Archive', 'doc-1', { revisionId: 'rev-1', retry: false });
  });

  it('reports queued progress without a percentage and polls until a terminal READY state', async () => {
    mocks.status
      .mockResolvedValueOnce(status({ state: 'QUEUED', totalPages: null, selectablePages: 0 }))
      .mockResolvedValueOnce(status({ state: 'BUILDING', totalPages: null, selectablePages: 0 }))
      .mockResolvedValue(status());
    render(SelectablePdfActions, { props: props() });

    expect(await screen.findByText('PDF with selectable text is queued.')).toBeTruthy();
    expect(await screen.findByText('Selectable text on 3 of 3 pages.')).toBeTruthy();
    expect(mocks.status.mock.calls.length).toBeGreaterThanOrEqual(3);
    const settled = mocks.status.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(mocks.status.mock.calls.length).toBe(settled);
    expect(document.querySelector('.pdf-actions')?.textContent ?? '').not.toContain('%');
  });

  it('opens and downloads a READY copy through exact file addresses that never generate', async () => {
    render(SelectablePdfActions, { props: props() });

    const open = await screen.findByRole('link', { name: 'Open PDF with selectable text' });
    const download = screen.getByRole('link', { name: 'Download PDF with selectable text' });
    expect(open.getAttribute('href')).toBe(`/api/collections/Archive/documents/doc-1/selectable-pdf/file?revision=rev-1&key=${KEY}&inline=true`);
    expect(open.getAttribute('target')).toBe('_blank');
    expect(download.getAttribute('href')).toBe(`/api/collections/Archive/documents/doc-1/selectable-pdf/file?revision=rev-1&key=${KEY}&inline=false`);
    expect(screen.getByText('Selectable text on 3 of 3 pages.')).toBeTruthy();
    expect(mocks.create).not.toHaveBeenCalled();
  });

  it('explains a verified blank copy as nothing to select instead of a failure or endless progress', async () => {
    mocks.status.mockResolvedValue(status({ selectablePages: 0, totalPages: 3, blankPages: 3 }));
    render(SelectablePdfActions, { props: props() });

    expect(await screen.findByText('Nothing to select here: all 3 pages are verified blank.')).toBeTruthy();
    expect(screen.queryByText(/failed|could not/i)).toBeNull();
    expect(screen.queryByText(/queued|being built/)).toBeNull();
    expect(screen.getByRole('link', { name: 'Open PDF with selectable text' })).toBeTruthy();
    await new Promise((resolve) => setTimeout(resolve, 15));
    expect(mocks.status).toHaveBeenCalledTimes(1);
  });

  it('lists a partial copy\'s unavailable pages with plain reasons before the download', async () => {
    mocks.status.mockResolvedValue(status({
      state: 'PARTIAL', selectablePages: 1, totalPages: 3, blankPages: 0,
      unavailablePages: [{ ordinal: 1, coverage: 'UNAVAILABLE', reasonCode: 'PDF_TEXT_ALIGNMENT_AMBIGUOUS' }],
    }));
    render(SelectablePdfActions, { props: props() });

    expect(await screen.findByText('Selectable text on 1 of 3 pages.')).toBeTruthy();
    const item = screen.getByText('Page 2: The recognized text could not be matched to its position on this page.');
    const download = screen.getByRole('link', { name: 'Download PDF with selectable text' });
    expect(item.compareDocumentPosition(download) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(screen.getByRole('button', { name: 'Retry PDF generation' })).toBeTruthy();
    expect(screen.getByRole('link', { name: 'Open PDF with selectable text' })).toBeTruthy();
  });

  it('shows a FAILED copy with a plain-English reason, never the raw code, and retries with retry true', async () => {
    mocks.status.mockResolvedValue(status({ state: 'FAILED', key: null, selectablePages: 0, totalPages: null, reasonCode: 'ARTIFACT_VERIFICATION_FAILED' }));
    mocks.create.mockResolvedValue(status({ state: 'QUEUED', totalPages: null, selectablePages: 0 }));
    render(SelectablePdfActions, { props: props({ pollMillis: 60_000 }) });

    expect(await screen.findByText(/The generated PDF did not pass its check/)).toBeTruthy();
    expect(screen.queryByText(/ARTIFACT_VERIFICATION_FAILED/)).toBeNull();
    await fireEvent.click(screen.getByRole('button', { name: 'Retry PDF generation' }));

    expect(mocks.create).toHaveBeenCalledWith('Archive', 'doc-1', { revisionId: 'rev-1', retry: true });
    expect(await screen.findByText('PDF with selectable text is queued.')).toBeTruthy();
    expect(document.activeElement?.textContent).toContain('PDF with selectable text is queued.');
  });

  it('never renders undefined or null text for an omitted-looking nullable status', async () => {
    mocks.status.mockResolvedValue(status({ state: 'QUEUED', totalPages: null, reasonCode: null, selectablePages: 0 }));
    render(SelectablePdfActions, { props: props({ pollMillis: 60_000 }) });

    expect(await screen.findByText('PDF with selectable text is queued.')).toBeTruthy();
    const text = document.querySelector('.pdf-actions')?.textContent ?? '';
    expect(text).not.toMatch(/undefined|null/);
  });
});

describe('SelectablePdfActions actions and failures', () => {
  it('converges a double click on Create into one request', async () => {
    mocks.status.mockResolvedValue(notCreated());
    const answer = deferred<PdfArtifactStatus>();
    mocks.create.mockReturnValue(answer.promise);
    render(SelectablePdfActions, { props: props() });

    const create = await screen.findByRole('button', { name: 'Create PDF with selectable text' });
    await fireEvent.click(create);
    await fireEvent.click(create);
    answer.resolve(status({ state: 'QUEUED', totalPages: null, selectablePages: 0 }));

    expect(await screen.findByText('PDF with selectable text is queued.')).toBeTruthy();
    expect(mocks.create).toHaveBeenCalledTimes(1);
  });

  it('keeps a failed create retryable instead of stuck', async () => {
    mocks.status.mockResolvedValue(notCreated());
    mocks.create
      .mockRejectedValueOnce(new ApiError('HTTP_500', 'internal detail'))
      .mockResolvedValueOnce(status({ state: 'QUEUED', totalPages: null, selectablePages: 0 }));
    render(SelectablePdfActions, { props: props({ pollMillis: 60_000 }) });

    await fireEvent.click(await screen.findByRole('button', { name: 'Create PDF with selectable text' }));
    expect(await screen.findByText('The PDF with selectable text could not be started. Try again.')).toBeTruthy();
    expect(screen.queryByText(/internal detail/)).toBeNull();
    const again = screen.getByRole('button', { name: 'Create PDF with selectable text' });
    expect((again as HTMLButtonElement).disabled).toBe(false);

    await fireEvent.click(again);
    expect(await screen.findByText('PDF with selectable text is queued.')).toBeTruthy();
    expect(mocks.create).toHaveBeenCalledTimes(2);
  });

  it('offers Check again after a failed status read and reads again when pressed', async () => {
    mocks.status
      .mockRejectedValueOnce(new ApiError('HTTP_500', 'boom'))
      .mockResolvedValue(notCreated());
    render(SelectablePdfActions, { props: props() });

    expect(await screen.findByText('The PDF status could not be loaded.')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Check again' }));
    expect(await screen.findByRole('button', { name: 'Create PDF with selectable text' })).toBeTruthy();
    expect(mocks.create).not.toHaveBeenCalled();
  });
});

describe('SelectablePdfActions identity changes and cancellation', () => {
  it('ignores a late answer for an earlier revision after the selected revision changes', async () => {
    const earlier = deferred<PdfArtifactStatus>();
    mocks.status.mockImplementation(async (_c: string, _d: string, revisionId: string) => {
      if (revisionId === 'rev-a') return earlier.promise;
      return status({ revisionId: 'rev-b', key: 'b'.repeat(64) });
    });
    const view = render(SelectablePdfActions, { props: props({ revisionId: 'rev-a' }) });

    await view.rerender({ revisionId: 'rev-b' });
    expect(await screen.findByText('Selectable text on 3 of 3 pages.')).toBeTruthy();
    earlier.resolve(status({ revisionId: 'rev-a', state: 'QUEUED', totalPages: null, selectablePages: 0, key: 'a'.repeat(64) }));
    await new Promise((resolve) => setTimeout(resolve, 15));

    expect(screen.queryByText('PDF with selectable text is queued.')).toBeNull();
    expect(screen.getByRole('link', { name: 'Open PDF with selectable text' }).getAttribute('href')).toContain('b'.repeat(64));
  });

  it('cancels an active poll for the earlier revision when the identity changes', async () => {
    mocks.status.mockImplementation(async (_c: string, _d: string, revisionId: string) => revisionId === 'rev-a'
      ? status({ revisionId: 'rev-a', state: 'QUEUED', totalPages: null, selectablePages: 0 })
      : { ...notCreated(), revisionId: 'rev-b' });
    const view = render(SelectablePdfActions, { props: props({ revisionId: 'rev-a' }) });
    expect(await screen.findByText('PDF with selectable text is queued.')).toBeTruthy();

    await view.rerender({ revisionId: 'rev-b' });
    await screen.findByRole('button', { name: 'Create PDF with selectable text' });
    const afterSwitch = mocks.status.mock.calls.filter((call) => call[2] === 'rev-a').length;
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(mocks.status.mock.calls.filter((call) => call[2] === 'rev-a').length).toBe(afterSwitch);
  });

  it('cancels an active poll when the component unmounts', async () => {
    mocks.status.mockResolvedValue(status({ state: 'QUEUED', totalPages: null, selectablePages: 0 }));
    const view = render(SelectablePdfActions, { props: props() });
    expect(await screen.findByText('PDF with selectable text is queued.')).toBeTruthy();

    view.unmount();
    const atUnmount = mocks.status.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(mocks.status.mock.calls.length).toBe(atUnmount);
  });

  it('stops polling while hidden and reads again without creating when shown', async () => {
    mocks.status.mockResolvedValue(status({ state: 'QUEUED', totalPages: null, selectablePages: 0 }));
    const view = render(SelectablePdfActions, { props: props() });
    await screen.findByText('PDF with selectable text is queued.');

    await view.rerender({ visible: false });
    const hiddenAt = mocks.status.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(mocks.status.mock.calls.length).toBe(hiddenAt);

    mocks.status.mockResolvedValue(status());
    await view.rerender({ visible: true });
    expect(await screen.findByText('Selectable text on 3 of 3 pages.')).toBeTruthy();
    expect(mocks.create).not.toHaveBeenCalled();
  });

  it('stops polling when the document itself becomes hidden', async () => {
    mocks.status.mockResolvedValue(status({ state: 'BUILDING', totalPages: null, selectablePages: 0 }));
    render(SelectablePdfActions, { props: props() });
    await screen.findByText('PDF with selectable text is being built.');

    Object.defineProperty(document, 'hidden', { value: true, configurable: true });
    document.dispatchEvent(new Event('visibilitychange'));
    const hiddenAt = mocks.status.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 30));
    expect(mocks.status.mock.calls.length).toBe(hiddenAt);
  });
});

describe('SelectablePdfActions layout and keyboard', () => {
  it('lets the actions and a long page reason wrap instead of overflowing or hiding text', async () => {
    mocks.status.mockResolvedValue(status({
      state: 'PARTIAL', selectablePages: 1, totalPages: 2, blankPages: 0,
      unavailablePages: [{ ordinal: 1, coverage: 'UNAVAILABLE', reasonCode: 'PDF_TEXT_ALIGNMENT_LIMIT_EXCEEDED' }],
    }));
    render(SelectablePdfActions, { props: props() });

    const item = await screen.findByText(/^Page 2: /);
    const group = document.querySelector('.pdf-actions') as HTMLElement;
    expect(getComputedStyle(group).flexWrap).toBe('wrap');
    expect(getComputedStyle(item).overflowWrap).toBe('anywhere');
    expect(getComputedStyle(item).overflow).not.toBe('hidden');
    expect(getComputedStyle(item).maxWidth).toBe('100%');
    expect(item.textContent).toContain('Positioning this page took more work than allowed.');
  });

  it('keeps every control a named keyboard target and moves focus to the status line after an action', async () => {
    mocks.status.mockResolvedValue(notCreated());
    render(SelectablePdfActions, { props: props({ pollMillis: 60_000 }) });

    const create = await screen.findByRole('button', { name: 'Create PDF with selectable text' });
    create.focus();
    expect(document.activeElement).toBe(create);
    await fireEvent.click(create);
    await waitFor(() => expect(document.activeElement?.getAttribute('role')).toBe('status'));
    expect(document.activeElement?.textContent).toMatch(/queued|Starting/i);
  });
});
