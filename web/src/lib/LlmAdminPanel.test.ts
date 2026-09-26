import { cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import LlmAdminPanel from './LlmAdminPanel.svelte';
import type { LlmProfile } from './api';

const api = vi.hoisted(() => ({
  listLlmProfiles: vi.fn(),
  createLlmProfile: vi.fn(),
  updateLlmProfile: vi.fn(),
  deleteLlmProfile: vi.fn(),
  setLlmDefault: vi.fn(),
}));

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {},
  listLlmProfiles: api.listLlmProfiles,
  createLlmProfile: api.createLlmProfile,
  updateLlmProfile: api.updateLlmProfile,
  deleteLlmProfile: api.deleteLlmProfile,
  setLlmDefault: api.setLlmDefault,
}));

function profile(id: string, name: string, over: Partial<LlmProfile> = {}): LlmProfile {
  return {
    id,
    name,
    provider: 'OPENAI_COMPATIBLE',
    endpoint: 'https://example.test/v1',
    model: 'deepseek-chat',
    contextWindow: 128_000,
    maxOutputTokens: 4_096,
    inputPricePerMillion: 0.5,
    outputPricePerMillion: 1.5,
    cacheReadPricePerMillion: 0.1,
    enabled: true,
    apiKeyEnvironmentVariable: 'OPENAI_API_KEY',
    keyAvailable: true,
    toolCallingMeasured: null,
    capabilityCheckedAt: null,
    ...over,
  };
}

describe('LLM admin panel', () => {
  beforeEach(() => {
    vi.resetAllMocks();
  });

  afterEach(cleanup);

  it('loads the profile list and populates the form with the selected profile', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast'), profile('p2', 'slow')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });

    render(LlmAdminPanel);

    await screen.findByLabelText('Name');
    expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('fast');
    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('deepseek-chat');
    expect((screen.getByLabelText('Ask default') as HTMLSelectElement).value).toBe('p1');
    expect(api.listLlmProfiles).toHaveBeenCalledTimes(1);
  });

  it('creates a profile from the entered fields', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.createLlmProfile.mockImplementation(async (input) => profile('p2', input.name));

    render(LlmAdminPanel);
    await screen.findByLabelText('Name');

    await fireEvent.click(screen.getByRole('button', { name: 'New' }));
    await fireEvent.input(screen.getByLabelText('Name'), { target: { value: 'newbie' } });
    await fireEvent.input(screen.getByLabelText('Model'), { target: { value: 'model-x' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Create profile' }));

    expect(api.createLlmProfile).toHaveBeenCalledTimes(1);
    expect(api.createLlmProfile).toHaveBeenCalledWith(expect.objectContaining({
      name: 'newbie',
      model: 'model-x',
      provider: 'OPENAI_COMPATIBLE',
      enabled: true,
      apiKeyEnvironmentVariable: null,
    }));
  });

  it('disables Delete for the last remaining profile and deletes otherwise', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'only')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.deleteLlmProfile.mockResolvedValue(undefined);

    render(LlmAdminPanel);
    await screen.findByLabelText('Name');

    expect((screen.getByRole('button', { name: 'Delete' }) as HTMLButtonElement).disabled).toBe(true);
    expect(api.deleteLlmProfile).not.toHaveBeenCalled();
  });

  it('persists a new per-role default when selected', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast'), profile('p2', 'slow')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.setLlmDefault.mockResolvedValue(undefined);

    render(LlmAdminPanel);
    await screen.findByLabelText('Name');

    await fireEvent.change(screen.getByLabelText('Investigate default'), { target: { value: 'p2' } });

    expect(api.setLlmDefault).toHaveBeenCalledWith('INVESTIGATE', 'p2');
  });
});
