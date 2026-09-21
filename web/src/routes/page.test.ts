import { render, screen } from '@testing-library/svelte';
import { describe, expect, it } from 'vitest';
import Page from './+page.svelte';

describe('app shell', () => {
  it('renders the InfoScry heading', () => {
    render(Page);
    expect(screen.getByRole('heading', { level: 1, name: 'InfoScry' })).toBeTruthy();
  });
});
