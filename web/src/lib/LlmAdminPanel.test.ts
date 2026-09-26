import { act, cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import LlmAdminPanel from './LlmAdminPanel.svelte';
import type { LlmCatalog, LlmCatalogModel, LlmPreset, LlmProfile } from './api';

const api = vi.hoisted(() => ({
  listLlmProfiles: vi.fn(),
  listLlmPresets: vi.fn(),
  fetchLlmCatalog: vi.fn(),
  createLlmProfile: vi.fn(),
  updateLlmProfile: vi.fn(),
  deleteLlmProfile: vi.fn(),
  setLlmDefault: vi.fn(),
}));

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {},
  listLlmProfiles: api.listLlmProfiles,
  listLlmPresets: api.listLlmPresets,
  fetchLlmCatalog: api.fetchLlmCatalog,
  createLlmProfile: api.createLlmProfile,
  updateLlmProfile: api.updateLlmProfile,
  deleteLlmProfile: api.deleteLlmProfile,
  setLlmDefault: api.setLlmDefault,
}));

function preset(id: string, label: string, over: Partial<LlmPreset> = {}): LlmPreset {
  return {
    id,
    label,
    provider: 'OPENAI_COMPATIBLE',
    endpoint: 'https://example.test/v1',
    apiKeyEnvironmentVariable: null,
    ...over,
  };
}

function model(id: string, over: Partial<LlmCatalogModel> = {}): LlmCatalogModel {
  return {
    id,
    contextWindow: null,
    maxOutputTokens: null,
    inputPricePerMillion: null,
    outputPricePerMillion: null,
    cacheReadPricePerMillion: null,
    priceKnown: true,
    ...over,
  };
}

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
    api.listLlmPresets.mockResolvedValue([]);
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

  it('still lists profiles when only the presets route fails', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.listLlmPresets.mockRejectedValue(new Error('the presets service is unavailable'));

    render(LlmAdminPanel);

    await screen.findByLabelText('Name');
    expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('fast');
    expect(api.listLlmProfiles).toHaveBeenCalledTimes(1);
    expect(await screen.findByText('the presets service is unavailable')).toBeTruthy();
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

  it('applies the provider fields when a preset is chosen', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast', {
        endpoint: 'https://example.test/v1',
        apiKeyEnvironmentVariable: 'OTHER_KEY',
      })],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.listLlmPresets.mockResolvedValue([
      preset('OPENAI', 'OpenAI', {
        endpoint: 'https://api.openai.com/v1',
        apiKeyEnvironmentVariable: 'OPENAI_API_KEY',
      }),
    ]);

    render(LlmAdminPanel);
    await screen.findByLabelText('Name');

    await fireEvent.change(screen.getByLabelText('Provider preset'), { target: { value: 'OPENAI' } });

    expect((screen.getByLabelText('Endpoint (base URL)') as HTMLInputElement).value).toBe(
      'https://api.openai.com/v1',
    );
    expect((screen.getByLabelText('API key environment variable') as HTMLInputElement).value).toBe(
      'OPENAI_API_KEY',
    );
  });

  it('renders the fetched models and asks the catalog for the draft connection', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.listLlmPresets.mockResolvedValue([
      preset('OPENAI', 'OpenAI', {
        endpoint: 'https://api.openai.com/v1',
        apiKeyEnvironmentVariable: 'OPENAI_API_KEY',
      }),
    ]);
    api.fetchLlmCatalog.mockResolvedValue({
      live: true,
      models: [model('gpt-4o'), model('gpt-4o-mini')],
    });

    render(LlmAdminPanel);
    await screen.findByLabelText('Name');

    await fireEvent.change(screen.getByLabelText('Provider preset'), { target: { value: 'OPENAI' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }));

    expect(await screen.findByRole('option', { name: 'gpt-4o' })).toBeDefined();
    expect(api.fetchLlmCatalog).toHaveBeenCalledWith(
      'OPENAI_COMPATIBLE',
      'https://api.openai.com/v1',
      'OPENAI_API_KEY',
    );
  });

  it('copies a chosen model and leaves the fields it does not know untouched', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast', { contextWindow: 16_000 })],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.fetchLlmCatalog.mockResolvedValue({
      live: true,
      models: [model('gpt-4o', { contextWindow: 128_000, inputPricePerMillion: 2.5 })],
    });

    render(LlmAdminPanel);
    await screen.findByLabelText('Name');

    await fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }));
    await screen.findByLabelText('Model catalog');
    await fireEvent.change(screen.getByLabelText('Model catalog'), { target: { value: 'gpt-4o' } });

    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('gpt-4o');
    expect((screen.getByLabelText('Context window (tokens)') as HTMLInputElement).value).toBe('128000');
    expect((screen.getByLabelText('Input price (USD / 1M tokens)') as HTMLInputElement).value).toBe('2.5');
    expect((screen.getByLabelText('Max output tokens') as HTMLInputElement).value).toBe('4096');
  });

  it('shows the manual-price hint for a model with an unknown price', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.fetchLlmCatalog.mockResolvedValue({
      live: true,
      models: [model('gpt-4o', { priceKnown: false })],
    });

    render(LlmAdminPanel);
    await screen.findByLabelText('Name');

    await fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }));
    await screen.findByLabelText('Model catalog');
    await fireEvent.change(screen.getByLabelText('Model catalog'), { target: { value: 'gpt-4o' } });

    expect(screen.getByText('price unknown — enter manually')).toBeDefined();
  });

  it('suggests manual entry when no live model list comes back', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.fetchLlmCatalog.mockResolvedValue({ live: false, models: [] });

    render(LlmAdminPanel);
    await screen.findByLabelText('Name');

    await fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }));

    expect(await screen.findByText('Could not fetch models — enter one manually.')).toBeDefined();
  });

  it('drops the fetched models when a Provider preset changes the connection', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.listLlmPresets.mockResolvedValue([
      preset('OPENAI', 'OpenAI', {
        endpoint: 'https://api.openai.com/v1',
        apiKeyEnvironmentVariable: 'OPENAI_API_KEY',
      }),
      preset('ANTHROPIC', 'Anthropic', {
        provider: 'ANTHROPIC',
        endpoint: 'https://api.anthropic.com',
        apiKeyEnvironmentVariable: 'ANTHROPIC_API_KEY',
      }),
    ]);
    api.fetchLlmCatalog.mockResolvedValue({
      live: true,
      models: [model('gpt-4o')],
    });

    render(LlmAdminPanel);
    await screen.findByLabelText('Name');

    await fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }));
    expect(await screen.findByRole('option', { name: 'gpt-4o' })).toBeDefined();

    await fireEvent.change(screen.getByLabelText('Provider preset'), { target: { value: 'ANTHROPIC' } });

    expect((screen.getByLabelText('Endpoint (base URL)') as HTMLInputElement).value).toBe(
      'https://api.anthropic.com',
    );
    expect(screen.queryByLabelText('Model catalog')).toBeNull();
  });

  it('drops the fetched models when the endpoint is edited', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.fetchLlmCatalog.mockResolvedValue({
      live: true,
      models: [model('gpt-4o')],
    });

    render(LlmAdminPanel);
    await screen.findByLabelText('Name');

    await fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }));
    expect(await screen.findByRole('option', { name: 'gpt-4o' })).toBeDefined();

    await fireEvent.input(screen.getByLabelText('Endpoint (base URL)'), {
      target: { value: 'https://other.example.test/v1' },
    });

    expect(screen.queryByLabelText('Model catalog')).toBeNull();
  });

  it('ignores a stale fetch that settles after the connection changed', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.listLlmPresets.mockResolvedValue([
      preset('OPENAI', 'OpenAI', {
        endpoint: 'https://api.openai.com/v1',
        apiKeyEnvironmentVariable: 'OPENAI_API_KEY',
      }),
      preset('ANTHROPIC', 'Anthropic', {
        provider: 'ANTHROPIC',
        endpoint: 'https://api.anthropic.com',
        apiKeyEnvironmentVariable: 'ANTHROPIC_API_KEY',
      }),
    ]);
    const pending: Array<(catalog: LlmCatalog) => void> = [];
    api.fetchLlmCatalog.mockImplementation(
      () => new Promise<LlmCatalog>((resolve) => {
        pending.push(resolve);
      }),
    );

    render(LlmAdminPanel);
    await screen.findByLabelText('Name');

    await fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }));
    await fireEvent.change(screen.getByLabelText('Provider preset'), { target: { value: 'ANTHROPIC' } });
    pending[0]?.({ live: true, models: [model('gpt-4o')] });
    await act(async () => {});

    expect(screen.queryByLabelText('Model catalog')).toBeNull();
    expect((screen.getByRole('button', { name: 'Fetch models' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('keeps a newer fetch in flight when an older one settles late', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.listLlmPresets.mockResolvedValue([
      preset('OPENAI', 'OpenAI', {
        endpoint: 'https://api.openai.com/v1',
        apiKeyEnvironmentVariable: 'OPENAI_API_KEY',
      }),
      preset('ANTHROPIC', 'Anthropic', {
        provider: 'ANTHROPIC',
        endpoint: 'https://api.anthropic.com',
        apiKeyEnvironmentVariable: 'ANTHROPIC_API_KEY',
      }),
    ]);
    const pending: Array<(catalog: LlmCatalog) => void> = [];
    api.fetchLlmCatalog.mockImplementation(
      () => new Promise<LlmCatalog>((resolve) => {
        pending.push(resolve);
      }),
    );

    render(LlmAdminPanel);
    await screen.findByLabelText('Name');

    await fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }));
    await fireEvent.change(screen.getByLabelText('Provider preset'), { target: { value: 'ANTHROPIC' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }));
    expect(api.fetchLlmCatalog).toHaveBeenCalledTimes(2);

    pending[0]?.({ live: true, models: [model('gpt-4o')] });
    await act(async () => {});

    expect((screen.getByRole('button', { name: /Fetch/ }) as HTMLButtonElement).disabled).toBe(true);

    pending[1]?.({ live: true, models: [model('gpt-4o')] });
    await act(async () => {});

    expect((screen.getByRole('button', { name: /Fetch/ }) as HTMLButtonElement).disabled).toBe(false);
  });
});
