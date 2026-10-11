import { act, cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import LlmAdminPanel from './LlmAdminPanel.svelte';
import type { LlmCatalog, LlmCatalogModel, LlmPreset, LlmProfile, LlmProfileProbe, OcrProfile } from './api';

const api = vi.hoisted(() => ({
  listLlmProfiles: vi.fn(),
  listLlmPresets: vi.fn(),
  fetchLlmCatalog: vi.fn(),
  createLlmProfile: vi.fn(),
  updateLlmProfile: vi.fn(),
  deleteLlmProfile: vi.fn(),
  setLlmDefault: vi.fn(),
  probeLlmProfile: vi.fn(),
  listOcrProfiles: vi.fn(),
  copyLlmProfileToOcr: vi.fn(),
  probeOcrProfile: vi.fn(),
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
  probeLlmProfile: api.probeLlmProfile,
  listOcrProfiles: api.listOcrProfiles,
  copyLlmProfileToOcr: api.copyLlmProfileToOcr,
  probeOcrProfile: api.probeOcrProfile,
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
    imageInput: null,
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
    api.listOcrProfiles.mockResolvedValue([]);
  });

  afterEach(cleanup);

  it('edits sparse effort overrides and Clear returns the row to inherited defaults', async () => {
    api.listLlmProfiles.mockResolvedValue({ profiles: [profile('p1', 'fast')], defaults: { ASK: 'p1', INVESTIGATE: null } });
    api.updateLlmProfile.mockImplementation(async (_id, input) => profile('p1', 'fast', input));
    render(LlmAdminPanel);
    const rounds = await screen.findByLabelText('HIGH tool rounds');
    expect((rounds as HTMLInputElement).value).toBe('50');
    expect((screen.getByLabelText('LOW tool calls') as HTMLInputElement).value).toBe('15');
    await fireEvent.input(rounds, { target: { value: '7' } });
    await fireEvent.change(screen.getByLabelText('HIGH native reasoning'), { target: { value: 'high' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(api.updateLlmProfile).toHaveBeenCalledWith('p1', expect.objectContaining({
      effortOverrides: { HIGH: { limits: { maxToolRounds: 7, maxToolCalls: 75, maxTurnSeconds: 1200 }, reasoning: { type: 'OpenAiReasoningEffort', value: 'high' } } },
    })));
    await screen.findByText("'fast' saved.");
    await fireEvent.click(screen.getByRole('button', { name: 'Clear HIGH override' }));
    expect((screen.getByLabelText('HIGH tool rounds') as HTMLInputElement).value).toBe('50');
    await fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(api.updateLlmProfile).toHaveBeenLastCalledWith('p1', expect.objectContaining({ effortOverrides: null })));
  });

  it('offers an optional Anthropic token budget and clears it back to inherited limits', async () => {
    api.listLlmProfiles.mockResolvedValue({ profiles: [profile('p1', 'native', { provider: 'ANTHROPIC' })], defaults: { ASK: null, INVESTIGATE: null } });
    api.updateLlmProfile.mockImplementation(async (_id, input) => profile('p1', 'native', input));
    render(LlmAdminPanel);
    const budget = await screen.findByLabelText('HIGH native reasoning') as HTMLInputElement;
    expect(budget.type).toBe('number');
    expect(budget.min).toBe('1024');
    expect(budget.max).toBe('4095');
    expect(budget.value).toBe('');
    await fireEvent.input(budget, { target: { value: '2048' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await waitFor(() => expect(api.updateLlmProfile).toHaveBeenCalledWith('p1', expect.objectContaining({ effortOverrides: {
      HIGH: { limits: { maxToolRounds: 50, maxToolCalls: 75, maxTurnSeconds: 1200 }, reasoning: { type: 'AnthropicThinkingBudget', tokens: 2048 } },
    } })));
    await screen.findByText("'native' saved.");
    await fireEvent.click(screen.getByRole('button', { name: 'Clear HIGH override' }));
    expect((screen.getByLabelText('HIGH native reasoning') as HTMLInputElement).value).toBe('');
  });

  it('groups editable settings separately from saved-profile checks in the shared workspace', async () => {
    api.listLlmProfiles.mockResolvedValue({ profiles: [profile('p1', 'fast')], defaults: { ASK: 'p1', INVESTIGATE: null } });
    render(LlmAdminPanel);
    await screen.findByLabelText('Name');
    const settings = screen.getByRole('group', { name: 'Profile settings' });
    expect(screen.getByRole('region', { name: 'LLM profile administration' }).classList.contains('workspace-area')).toBe(true);

    expect(within(settings).getByLabelText('Name')).toBeTruthy();
    expect(within(settings).getByLabelText('Context window (tokens)')).toBeTruthy();
    const checks = screen.getByRole('group', { name: 'Profile checks' });
    expect(within(checks).getByRole('button', { name: 'Check tool calling' })).toBeTruthy();
    expect(within(checks).getByRole('button', { name: 'Check image reading' })).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'New' }));
    expect(screen.queryByRole('group', { name: 'Profile checks' })).toBeNull();
    expect(within(screen.getByRole('group', { name: 'Profile settings' })).getByLabelText('Name')).toBeTruthy();
    await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    expect(screen.getByRole('group', { name: 'Profile checks' })).toBeTruthy();
  });

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

  it('names a new profile from the provider and selected model until the name is edited', async () => {
    api.listLlmProfiles.mockResolvedValue({ profiles: [], defaults: { ASK: null, INVESTIGATE: null } });
    api.listLlmPresets.mockResolvedValue([preset('OPENROUTER', 'OpenRouter', { endpoint: 'https://openrouter.ai/api/v1' })]);
    api.fetchLlmCatalog.mockResolvedValue({ live: true, models: [model('gpt-luna-latest'), model('gpt-luna-small')] });
    render(LlmAdminPanel);
    await fireEvent.change(await screen.findByLabelText('Provider preset'), { target: { value: 'OPENROUTER' } });
    await screen.findByRole('option', { name: /gpt-luna-latest/ });
    const name = screen.getByLabelText('Name') as HTMLInputElement;
    const catalog = screen.getByLabelText('Model catalog');
    await fireEvent.change(catalog, { target: { value: 'gpt-luna-latest' } });
    expect(name.value).toBe('OpenRouter/gpt-luna-latest');
    await fireEvent.change(catalog, { target: { value: 'gpt-luna-small' } });
    expect(name.value).toBe('OpenRouter/gpt-luna-small');
    await fireEvent.input(name, { target: { value: 'My OCR reader' } });
    await fireEvent.change(catalog, { target: { value: 'gpt-luna-latest' } });
    expect(name.value).toBe('My OCR reader');
    // Starting another new profile clears the manual-name choice.
    await fireEvent.click(screen.getByRole('button', { name: 'Cancel' }));
    await fireEvent.click(screen.getByRole('button', { name: 'New' }));
    expect((screen.getByLabelText('Provider preset') as HTMLSelectElement).value).toBe('');
    await fireEvent.change(screen.getByLabelText('Provider preset'), { target: { value: 'OPENROUTER' } });
    await screen.findByRole('option', { name: /gpt-luna-latest/ });
    await fireEvent.change(screen.getByLabelText('Model catalog'), { target: { value: 'gpt-luna-latest' } });
    expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('OpenRouter/gpt-luna-latest');
  });

  it('keeps an existing profile name when another model is selected', async () => {
    api.listLlmProfiles.mockResolvedValue({ profiles: [profile('p1', 'My reader', { endpoint: 'https://openrouter.ai/api/v1' })], defaults: { ASK: 'p1', INVESTIGATE: null } });
    api.fetchLlmCatalog.mockResolvedValue({ live: true, models: [model('gpt-luna-latest')] });
    render(LlmAdminPanel);
    await screen.findByRole('option', { name: /gpt-luna-latest/ });
    await fireEvent.change(screen.getByLabelText('Model catalog'), { target: { value: 'gpt-luna-latest' } });
    expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('My reader');
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
      models: [
        model('gpt-4o', { inputPricePerMillion: 2.5, outputPricePerMillion: 10 }),
        model('gpt-4o-mini'),
      ],
    });

    render(LlmAdminPanel);
    await screen.findByLabelText('Name');

    await fireEvent.change(screen.getByLabelText('Provider preset'), { target: { value: 'OPENAI' } });

    expect(await screen.findByRole('option', { name: 'gpt-4o — $2.5 in / $10 out per 1M tokens' })).toBeDefined();
    expect(screen.getByRole('option', { name: 'gpt-4o-mini — price unknown' })).toBeDefined();
    expect(api.fetchLlmCatalog).toHaveBeenCalledWith(
      'OPENAI_COMPATIBLE',
      'https://api.openai.com/v1',
      'OPENAI_API_KEY',
    );
  });

  it('does not ask the provider while the endpoint is being typed, only when the field is left', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast', { endpoint: 'https://api.example.test/v1' })],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.fetchLlmCatalog.mockResolvedValue({ live: true, models: [model('gpt-4o')] });

    render(LlmAdminPanel);
    await waitFor(() => expect(api.fetchLlmCatalog).toHaveBeenCalledTimes(1));
    const endpoint = screen.getByLabelText('Endpoint (base URL)');

    // A real focus, as a person clicking into the field causes.
    await act(async () => (endpoint as HTMLInputElement).focus());
    await fireEvent.input(endpoint, { target: { value: 'https://api.exam' } });
    // Longer than the fetch delay: a half-typed address must still not be asked.
    await new Promise((resolve) => setTimeout(resolve, 600));
    expect(api.fetchLlmCatalog).toHaveBeenCalledTimes(1);

    await fireEvent.input(endpoint, { target: { value: 'https://api.other.test/v1' } });
    await act(async () => (endpoint as HTMLInputElement).blur());
    await waitFor(() => expect(api.fetchLlmCatalog).toHaveBeenCalledTimes(2));
    expect(api.fetchLlmCatalog).toHaveBeenLastCalledWith('OPENAI_COMPATIBLE', 'https://api.other.test/v1', 'OPENAI_API_KEY');
  });

  it('marks which listed models accept images and filters on it', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.fetchLlmCatalog.mockResolvedValue({
      live: true,
      models: [
        model('vendor/vision', { imageInput: true }),
        model('vendor/text', { imageInput: false }),
        model('vendor/silent', { imageInput: null }),
      ],
    });

    render(LlmAdminPanel);
    expect(await screen.findByRole('option', { name: /vendor\/vision.*image/ })).toBeDefined();
    expect(screen.getByRole('option', { name: /vendor\/text.*text only/ })).toBeDefined();
    expect(screen.getByRole('option', { name: /vendor\/silent/ })).toBeDefined();

    await fireEvent.click(screen.getByLabelText('Only models that accept images'));

    expect(screen.getByRole('option', { name: /vendor\/vision/ })).toBeDefined();
    expect(screen.queryByRole('option', { name: /vendor\/text/ })).toBeNull();
    expect(screen.queryByRole('option', { name: /vendor\/silent/ })).toBeNull();
  });

  it('cannot filter on image input when the provider states none', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.fetchLlmCatalog.mockResolvedValue({ live: true, models: [model('gpt-4o'), model('gpt-4o-mini')] });

    render(LlmAdminPanel);
    await screen.findByRole('option', { name: /gpt-4o-mini/ });

    expect((screen.getByLabelText('Only models that accept images') as HTMLInputElement).disabled).toBe(true);
    expect(screen.getByText("This provider's model list does not say which models accept images.")).toBeDefined();
  });

  it('sorts the listed models by price with unknown prices last', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast')],
      defaults: { ASK: 'p1', INVESTIGATE: null },
    });
    api.fetchLlmCatalog.mockResolvedValue({
      live: true,
      models: [
        model('b-mid', { inputPricePerMillion: 1, outputPricePerMillion: 2 }),
        model('a-unknown', { inputPricePerMillion: null, outputPricePerMillion: null, priceKnown: false }),
        model('c-cheap', { inputPricePerMillion: 0.1, outputPricePerMillion: 0.2 }),
        model('d-dear', { inputPricePerMillion: 5, outputPricePerMillion: 15 }),
      ],
    });
    const order = () => screen.getAllByRole('option').map((option) => option.textContent ?? '')
      .filter((text) => /^[a-d]-/.test(text)).map((text) => text.split(' ')[0]);

    render(LlmAdminPanel);
    await screen.findByRole('option', { name: /d-dear/ });
    expect(order()).toEqual(['b-mid', 'a-unknown', 'c-cheap', 'd-dear']);

    await fireEvent.change(screen.getByLabelText('Sort models'), { target: { value: 'price-asc' } });
    expect(order()).toEqual(['c-cheap', 'b-mid', 'd-dear', 'a-unknown']);

    await fireEvent.change(screen.getByLabelText('Sort models'), { target: { value: 'price-desc' } });
    expect(order()).toEqual(['d-dear', 'b-mid', 'c-cheap', 'a-unknown']);
  });

  it('keeps token defaults when the catalog omits unknown metadata', async () => {
    api.listLlmProfiles.mockResolvedValue({ profiles: [], defaults: { ASK: null, INVESTIGATE: null } });
    api.listLlmPresets.mockResolvedValue([preset('OLLAMA', 'Ollama (local)', { endpoint: 'http://localhost:11434/v1' })]);
    const listed = model('qwen3-vl:8b', { inputPricePerMillion: 0, outputPricePerMillion: 0 });
    const { contextWindow, maxOutputTokens, ...omittedLimits } = listed;
    api.fetchLlmCatalog.mockResolvedValue({ live: true, models: [omittedLimits] });
    render(LlmAdminPanel);
    await fireEvent.change(await screen.findByLabelText('Provider preset'), { target: { value: 'OLLAMA' } });
    await screen.findByRole('option', { name: /qwen3-vl:8b/ });
    await fireEvent.change(screen.getByLabelText('Model catalog'), { target: { value: 'qwen3-vl:8b' } });
    expect((screen.getByLabelText('Context window (tokens)') as HTMLInputElement).value).toBe('128000');
    expect((screen.getByLabelText('Max output tokens') as HTMLInputElement).value).toBe('4096');
    await fireEvent.click(screen.getByRole('button', { name: 'Create profile' }));
    expect(api.createLlmProfile).toHaveBeenCalledWith(expect.objectContaining({ contextWindow: 128000, maxOutputTokens: 4096 }));
  });

  it.each([
    ['Context window (tokens)', '', 'positive whole numbers'],
    ['Max output tokens', '0', 'positive whole numbers'],
    ['Max output tokens', '1.5', 'positive whole numbers'],
    ['Max output tokens', '500000', 'less than the context window'],
    ['Max output tokens', '128000', 'less than the context window'],
  ])('rejects invalid %s value %s before saving', async (field, value, message) => {
    api.listLlmProfiles.mockResolvedValue({ profiles: [profile('p1', 'fast')], defaults: { ASK: null, INVESTIGATE: null } });
    render(LlmAdminPanel);
    const input = await screen.findByLabelText(field) as HTMLInputElement;
    await fireEvent.input(input, { target: { value } });
    await fireEvent.submit(input.closest('form')!);
    expect(await screen.findByText(new RegExp(message))).toBeTruthy();
    expect(api.updateLlmProfile).not.toHaveBeenCalled();
    expect(api.createLlmProfile).not.toHaveBeenCalled();
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

    expect(await screen.findByRole('option', { name: 'gpt-4o — price unknown' })).toBeDefined();

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

    expect(await screen.findByRole('option', { name: 'gpt-4o — price unknown' })).toBeDefined();

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

    // The profile's own connection is fetched as soon as it is shown.
    await waitFor(() => expect(api.fetchLlmCatalog).toHaveBeenCalledTimes(1));
    await fireEvent.change(screen.getByLabelText('Provider preset'), { target: { value: 'ANTHROPIC' } });
    pending[0]?.({ live: true, models: [model('gpt-4o')] });
    await act(async () => {});

    expect(screen.queryByLabelText('Model catalog')).toBeNull();
    // The new connection is fetched on its own, and only its answer is shown.
    await waitFor(() => expect(api.fetchLlmCatalog).toHaveBeenCalledTimes(2));
    pending[1]?.({ live: true, models: [model('claude-sonnet')] });
    expect(await screen.findByRole('option', { name: /claude-sonnet/ })).toBeDefined();
    expect(screen.queryByRole('option', { name: /gpt-4o/ })).toBeNull();
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

    await waitFor(() => expect(api.fetchLlmCatalog).toHaveBeenCalledTimes(1));
    await fireEvent.change(screen.getByLabelText('Provider preset'), { target: { value: 'ANTHROPIC' } });
    await waitFor(() => expect(api.fetchLlmCatalog).toHaveBeenCalledTimes(2));

    // The older answer settles while the newer request is still in flight: it is dropped and the newer
    // request keeps showing that it is fetching.
    pending[0]?.({ live: true, models: [model('gpt-4o')] });
    await act(async () => {});
    expect(screen.queryByLabelText('Model catalog')).toBeNull();
    expect(screen.getByText('Fetching models…')).toBeDefined();

    pending[1]?.({ live: true, models: [model('claude-sonnet')] });
    expect(await screen.findByRole('option', { name: /claude-sonnet/ })).toBeDefined();
    expect(screen.queryByText('Fetching models…')).toBeNull();
  });

  it('shows the measured tool-calling state of the selected profile', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [
        profile('p1', 'fast', { toolCallingMeasured: false, capabilityCheckedAt: '2026-10-07T10:00:00Z' }),
        profile('p2', 'slow'),
      ],
      defaults: { ASK: null, INVESTIGATE: null },
    });

    render(LlmAdminPanel);

    expect(await screen.findByText('Tool calling: unsupported (measured 2026-10-07T10:00:00Z)')).toBeTruthy();
    await fireEvent.change(screen.getByLabelText('Profile'), { target: { value: 'p2' } });
    expect(screen.getByText('Tool calling: not measured')).toBeTruthy();
  });

  it('measures tool calling once however often Check tool calling is clicked, and shows the result', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast')],
      defaults: { ASK: 'p1', INVESTIGATE: 'p1' },
    });
    const measured = profile('p1', 'fast', { toolCallingMeasured: true, capabilityCheckedAt: '2026-10-07T11:00:00Z' });
    let finish!: (value: LlmProfileProbe) => void;
    api.probeLlmProfile.mockImplementation(() => new Promise<LlmProfileProbe>((resolve) => { finish = resolve; }));

    render(LlmAdminPanel);
    const check = await screen.findByRole('button', { name: 'Check tool calling' });
    await fireEvent.click(check);
    await fireEvent.click(check);

    expect(api.probeLlmProfile).toHaveBeenCalledTimes(1);
    expect(api.probeLlmProfile).toHaveBeenCalledWith('p1');
    expect((screen.getByRole('button', { name: 'Checking…' }) as HTMLButtonElement).disabled).toBe(true);

    api.listLlmProfiles.mockResolvedValue({ profiles: [measured], defaults: { ASK: 'p1', INVESTIGATE: 'p1' } });
    await act(async () => {
      finish({ profile: measured, textRequestSupported: true, toolCallingSupported: true });
    });

    expect(await screen.findByText('Tool calling supported: this profile can be used for Investigate.')).toBeTruthy();
    expect(screen.getByText('Tool calling: supported (measured 2026-10-07T11:00:00Z)')).toBeTruthy();
  });

  it('reports a refused check with the server message and does not claim a measurement', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'fast', { enabled: false })],
      defaults: { ASK: null, INVESTIGATE: null },
    });
    api.probeLlmProfile.mockRejectedValue(new Error("the LLM profile 'fast' is disabled; enable it before checking tool calling"));

    render(LlmAdminPanel);
    await fireEvent.click(await screen.findByRole('button', { name: 'Check tool calling' }));

    expect(await screen.findByText("the LLM profile 'fast' is disabled; enable it before checking tool calling")).toBeTruthy();
    expect(screen.getByText('Tool calling: not measured')).toBeTruthy();
  });
  it('copies the profile for OCR, measures image reading and shows the stored result', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'vision')],
      defaults: { ASK: 'p1', INVESTIGATE: 'p1' },
    });
    api.listOcrProfiles.mockResolvedValue([]);
    const copy = { id: 'o1', sourceLlmProfileId: 'p1', imageCapabilityMeasured: null, imageCapabilityCheckedAt: null } as OcrProfile;
    const measured = { ...copy, imageCapabilityMeasured: true, imageCapabilityCheckedAt: '2026-10-09T10:00:00Z' } as OcrProfile;
    api.copyLlmProfileToOcr.mockResolvedValue(copy);
    api.probeOcrProfile.mockResolvedValue({ profile: measured, supported: true });

    render(LlmAdminPanel);
    expect(await screen.findByText('Image reading for OCR: not checked')).toBeTruthy();
    api.listOcrProfiles.mockResolvedValue([measured]);
    await fireEvent.click(screen.getByRole('button', { name: 'Check image reading' }));

    expect(api.copyLlmProfileToOcr).toHaveBeenCalledWith('p1');
    expect(api.probeOcrProfile).toHaveBeenCalledWith('o1');
    expect(await screen.findByText('Image reading for OCR: confirmed (measured 2026-10-09T10:00:00Z)')).toBeTruthy();
  });

  it.each([
    { status: 402, code: 'OCR_PROVIDER_REFUSED', reason: 'the provider refused the request (HTTP 402), so this page was not read' },
    { status: 302, code: 'OCR_REDIRECT_REFUSED', reason: 'the provider answered a redirect; the redirect was not followed' },
  ])('shows the safe reason, HTTP $status and code for a failed image check', async ({ status, code, reason }) => {
    api.listLlmProfiles.mockResolvedValue({ profiles: [profile('p1', 'vision')], defaults: { ASK: 'p1', INVESTIGATE: 'p1' } });
    api.listOcrProfiles.mockResolvedValue([]);
    const copy = { id: 'o1', sourceLlmProfileId: 'p1', imageCapabilityMeasured: false } as OcrProfile;
    api.copyLlmProfileToOcr.mockResolvedValue(copy);
    api.probeOcrProfile.mockResolvedValue({
      profile: copy, supported: false, errorCode: code, statusCode: status, errorMessage: reason,
    });
    render(LlmAdminPanel);
    await fireEvent.click(await screen.findByRole('button', { name: 'Check image reading' }));
    const failure = await screen.findByRole('alert');
    expect(failure.textContent).toContain('Image reading check failed:');
    expect(failure.textContent).toContain(reason);
    expect(failure.textContent).toContain(`HTTP ${status}`);
    expect(failure.textContent).toContain(code);
    expect(screen.queryByText(/this model did not read the test image/)).toBeNull();
  });

  it('shows the server message when a text-only model is refused for OCR', async () => {
    api.listLlmProfiles.mockResolvedValue({
      profiles: [profile('p1', 'text')],
      defaults: { ASK: 'p1', INVESTIGATE: 'p1' },
    });
    api.listOcrProfiles.mockResolvedValue([]);
    api.copyLlmProfileToOcr.mockRejectedValue(new Error('this model does not accept images'));

    render(LlmAdminPanel);
    await fireEvent.click(await screen.findByRole('button', { name: 'Check image reading' }));

    expect(await screen.findByText('this model does not accept images')).toBeTruthy();
    expect(api.probeOcrProfile).not.toHaveBeenCalled();
    expect(screen.getByText('Image reading for OCR: not checked')).toBeTruthy();
  });
});
