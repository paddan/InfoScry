import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/svelte';
import { afterEach, describe, expect, it, vi } from 'vitest';
import SourceWorkspace from './SourceWorkspace.svelte';

describe('SourceWorkspace', () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 1440 });
  });

  it('uses the available workspace width and gives Back to the context pane on narrow layouts', async () => {
    const originalWidth = window.innerWidth;
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 });
    const onBack = vi.fn();
    render(SourceWorkspace, {
      props: {
        mode: 'split',
        sourceSnippet: 'Page 12',
        sourceOpen: true,
        onClose: vi.fn(),
        onBack,
      },
    });

    const workspace = document.querySelector('.source-workspace');
    expect(workspace?.getAttribute('data-layout')).toBe('narrow');
    expect(await screen.findByRole('button', { name: 'Back to results' })).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Back to results' }));
    expect(onBack).toHaveBeenCalledOnce();
    expect(await screen.findByRole('button', { name: 'Open selected source' })).toBeTruthy();
    expect(screen.getByText('Page 12')).toBeTruthy();

    Object.defineProperty(window, 'innerWidth', { configurable: true, value: originalWidth });
  });

  it('uses the measured container width when it differs from the viewport width', async () => {
    Object.defineProperty(window, 'innerWidth', { configurable: true, value: 390 });
    class WideWorkspaceObserver {
      constructor(private readonly callback: ResizeObserverCallback) {}
      observe(): void {
        this.callback([{ contentRect: { width: 1120 } } as ResizeObserverEntry], this as unknown as ResizeObserver);
      }
      disconnect(): void {}
    }
    vi.stubGlobal('ResizeObserver', WideWorkspaceObserver);
    render(SourceWorkspace, {
      props: {
        mode: 'split',
        sourceSnippet: 'Page 12',
        sourceOpen: true,
        onClose: vi.fn(),
        onBack: vi.fn(),
      },
    });

    await waitFor(() => expect(document.querySelector('.source-workspace')?.getAttribute('data-layout')).toBe('split'));
  });
});
