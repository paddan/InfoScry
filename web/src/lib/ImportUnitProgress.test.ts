import { cleanup, render, screen } from '@testing-library/svelte';
import { afterEach, expect, it } from 'vitest';
import ImportUnitProgress from './ImportUnitProgress.svelte';

afterEach(cleanup);

it('uses an indeterminate bar until the total is known and resets for the next document', async () => {
  const view = render(ImportUnitProgress, { progress: { unitKind: 'PAGE', processedUnits: 3, totalUnits: null, failedUnits: 0 } });
  let bar = screen.getByRole('progressbar', { name: 'Page progress' });
  expect(bar.hasAttribute('value')).toBe(false);
  expect(screen.getByText('3 pages processed')).toBeTruthy();
  await view.rerender({ progress: { unitKind: 'PAGE', processedUnits: 3, totalUnits: 40, failedUnits: 0 } });
  bar = screen.getByRole('progressbar', { name: 'Page progress' });
  expect(bar.getAttribute('value')).toBe('3');
  expect(bar.getAttribute('max')).toBe('40');
  await view.rerender({ progress: { unitKind: 'PAGE', processedUnits: 0, totalUnits: 10, failedUnits: 0 } });
  expect(bar.getAttribute('value')).toBe('0');
  expect(bar.getAttribute('max')).toBe('10');
  await view.rerender({ progress: null });
  expect(screen.queryByRole('progressbar')).toBeNull();
});
