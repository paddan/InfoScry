import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { ImportPreview, ReadingMethodList, RescanPreview } from './api';
import StartReadingDialog from './StartReadingDialog.svelte';
import * as api from './api';

vi.mock('./api', async (importOriginal) => ({
  ...(await importOriginal<typeof import('./api')>()),
  listReadingMethods: vi.fn(), previewImport: vi.fn(), startImport: vi.fn(),
  previewRescan: vi.fn(), startRescan: vi.fn(),
}));

const methods: ReadingMethodList = { default: 'surya', methods: [
  { method: 'tesseract', label: 'Tesseract', destination: 'this machine', available: true, unavailableReason: null, external: false },
  { method: 'surya', label: 'Surya', destination: 'this machine', available: true, unavailableReason: null, external: false },
  { method: 'llm:p1', label: 'Cloud OCR', destination: 'api.example.com', available: false, unavailableReason: 'Key variable is missing', external: true },
] };
const importPreview: ImportPreview = {
  files: [{ path: '/data/report.pdf', pages: 48, reason: null }], totalPages: 48, atLeast: false,
  destination: 'this machine', external: false, estimatedCostUsd: null, costBasis: null, previewHash: 'hash-1',
};
const rescanPreview = (over: Partial<RescanPreview> = {}): RescanPreview => ({
  previewId: 'preview-1', documentId: 'doc-1', managedHash: 'hash', snapshot: {} as RescanPreview['snapshot'],
  pageTotal: 48, externalPageUpperBound: 48, destinations: [], costEstimate: null, costUnavailableReason: null,
  ...over,
});

describe('StartReadingDialog', () => {
  afterEach(() => cleanup());

  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(api.listReadingMethods).mockResolvedValue(methods);
    vi.mocked(api.previewImport).mockResolvedValue(importPreview);
    vi.mocked(api.previewRescan).mockResolvedValue(rescanPreview());
    vi.mocked(api.startImport).mockResolvedValue({ job: { id: 'job-1' } as api.JobApiView });
    vi.mocked(api.startRescan).mockResolvedValue({ operationId: 'op-1' } as api.RescanStarted);
  });

  it('preselects the collection default, shows unavailable reasons and previews the selected method', async () => {
    render(StartReadingDialog, { collectionId: 'c', request: { kind: 'import', paths: ['/data/report.pdf'], recursive: false, include: [], exclude: [] }, onstarted: vi.fn(), oncancel: vi.fn() });
    const select = await screen.findByLabelText('Reading method') as HTMLSelectElement;
    await waitFor(() => expect(select.value).toBe('surya'));
    expect((screen.getByRole('option', { name: /Cloud OCR — unavailable: Key variable is missing/ }) as HTMLOptionElement).disabled).toBe(true);
    expect(api.previewImport).toHaveBeenLastCalledWith({ collection: 'c', paths: ['/data/report.pdf'], recursive: false, include: [], exclude: [], method: 'surya' });
    await fireEvent.change(select, { target: { value: 'tesseract' } });
    await waitFor(() => expect(api.previewImport).toHaveBeenLastCalledWith(expect.objectContaining({ method: 'tesseract' })));
  });

  it('names the confirmed method and page total and submits once with the preview hash', async () => {
    const onstarted = vi.fn();
    render(StartReadingDialog, { collectionId: 'c', request: { kind: 'import', paths: ['/data/report.pdf'], recursive: false, include: [], exclude: [] }, onstarted, oncancel: vi.fn() });
    const confirm = await screen.findByRole('button', { name: 'Read 48 pages with Surya' });
    await fireEvent.click(confirm);
    await fireEvent.click(confirm);
    await waitFor(() => expect(api.startImport).toHaveBeenCalledTimes(1));
    expect(api.startImport).toHaveBeenCalledWith(expect.objectContaining({ method: 'surya', previewHash: 'hash-1' }));
    expect(vi.mocked(api.startImport).mock.calls[0][0].requestId).toMatch(/.+/);
    expect(onstarted).toHaveBeenCalledTimes(1);
  });

  it('explains the all-pages scope and hides an exact cost when the external page count is unknown', async () => {
    vi.mocked(api.listReadingMethods).mockResolvedValue({
      default: 'llm:p1', methods: [{ ...methods.methods[2], available: true, unavailableReason: null }],
    });
    vi.mocked(api.previewImport).mockResolvedValue({ ...importPreview, files: [{ path: '/data/report.pdf', pages: null, reason: 'page count unknown' }], atLeast: true, external: true, destination: 'api.example.com', estimatedCostUsd: 3.25, costBasis: 'estimated from 48 pages' });
    render(StartReadingDialog, { collectionId: 'c', request: { kind: 'import', paths: ['/data/report.pdf'], recursive: false, include: [], exclude: [] }, onstarted: vi.fn(), oncancel: vi.fn() });
    await screen.findByRole('button', { name: 'Send at least 48 pages to api.example.com with Cloud OCR' });
    expect(screen.getByText('All pages in the listed files will be sent; total cost is unavailable because the page count is unknown.')).toBeTruthy();
    expect(screen.queryByText(/\$3\.2500/)).toBeNull();
  });

  it('re-previews a stale preview and explains why', async () => {
    vi.mocked(api.startImport).mockRejectedValueOnce(new api.ApiError('PREVIEW_STALE', 'stale'));
    render(StartReadingDialog, { collectionId: 'c', request: { kind: 'import', paths: ['/data/report.pdf'], recursive: false, include: [], exclude: [] }, onstarted: vi.fn(), oncancel: vi.fn() });
    await fireEvent.click(await screen.findByRole('button', { name: 'Read 48 pages with Surya' }));
    expect(await screen.findByText('The files changed; review the summary again.')).toBeTruthy();
    await waitFor(() => expect(api.previewImport).toHaveBeenCalledTimes(2));
  });

  it('refreshes a stale rescan preview using the server code', async () => {
    vi.mocked(api.startRescan).mockRejectedValueOnce(new api.ApiError('STALE_RESCAN_PREVIEW', 'stale', 409));
    render(StartReadingDialog, { collectionId: 'c', request: { kind: 'rescan', documentId: 'doc-1' }, onstarted: vi.fn(), oncancel: vi.fn() });

    await fireEvent.click(await screen.findByRole('button', { name: 'Read 48 pages with Surya' }));

    expect(await screen.findByText('The document changed; review the summary again.')).toBeTruthy();
    await waitFor(() => expect(api.previewRescan).toHaveBeenCalledTimes(2));
  });

  it('replays the exact start body after a lost response and blocks method changes or closing', async () => {
    vi.mocked(api.startImport).mockRejectedValueOnce(new TypeError('NetworkError'));
    const onstarted = vi.fn();
    const oncancel = vi.fn();
    render(StartReadingDialog, { collectionId: 'c', request: { kind: 'import', paths: ['/data/report.pdf'], recursive: false, include: [], exclude: [] }, onstarted, oncancel });

    await fireEvent.click(await screen.findByRole('button', { name: 'Read 48 pages with Surya' }));
    await screen.findByRole('alert');

    const method = screen.getByLabelText('Reading method') as HTMLSelectElement;
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    expect(method.disabled).toBe(true);
    expect(cancel.hasAttribute('disabled')).toBe(true);
    expect(screen.getByText(/Retry this exact start/)).toBeTruthy();
    await fireEvent.change(method, { target: { value: 'tesseract' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Retry same start' }));

    await waitFor(() => expect(api.startImport).toHaveBeenCalledTimes(2));
    expect(vi.mocked(api.startImport).mock.calls[1][0]).toEqual(vi.mocked(api.startImport).mock.calls[0][0]);
    expect(api.previewImport).toHaveBeenCalledTimes(1);
    expect(oncancel).not.toHaveBeenCalled();
    expect(onstarted).toHaveBeenCalledTimes(1);
  });

  it('traps Tab in the dialog and restores focus to the opener when closed', async () => {
    const opener = document.createElement('button');
    document.body.append(opener);
    opener.focus();
    const { unmount } = render(StartReadingDialog, { collectionId: 'c', request: { kind: 'import', paths: ['/data/report.pdf'], recursive: false, include: [], exclude: [] }, onstarted: vi.fn(), oncancel: vi.fn() });
    const dialog = await screen.findByTestId('start-reading-dialog');
    const method = await screen.findByLabelText('Reading method');
    const cancel = screen.getByRole('button', { name: 'Cancel' });

    expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Start import' }));
    cancel.focus();
    await fireEvent.keyDown(dialog, { key: 'Tab' });
    expect(document.activeElement).toBe(method);
    await fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
    expect(document.activeElement).toBe(cancel);

    await unmount();
    expect(document.activeElement).toBe(opener);
    opener.remove();
  });
});
