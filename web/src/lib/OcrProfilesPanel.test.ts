import { act, cleanup, fireEvent, render, screen, within } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import OcrProfilesPanel from './OcrProfilesPanel.svelte';
import type { OcrProfile, OcrProfileProbe } from './api';

const api = vi.hoisted(() => ({
  listOcrProfiles: vi.fn(),
  createOcrProfile: vi.fn(),
  updateOcrProfile: vi.fn(),
  disableOcrProfile: vi.fn(),
  probeOcrProfile: vi.fn(),
  listLlmPresets: vi.fn(),
  fetchLlmCatalog: vi.fn(),
}));

vi.mock('./api', () => ({
  ApiError: class ApiError extends Error {
    code: string;
    status: number | null;
    constructor(code: string, message: string, status: number | null = null) {
      super(message);
      this.code = code;
      this.status = status;
    }
  },
  listOcrProfiles: api.listOcrProfiles,
  createOcrProfile: api.createOcrProfile,
  updateOcrProfile: api.updateOcrProfile,
  disableOcrProfile: api.disableOcrProfile,
  probeOcrProfile: api.probeOcrProfile,
  listLlmPresets: api.listLlmPresets,
  fetchLlmCatalog: api.fetchLlmCatalog,
}));

async function apiError(code: string, message: string, status: number | null): Promise<Error> {
  const { ApiError } = await import('./api');
  return new ApiError(code, message, status);
}

function profile(id: string, name: string, over: Partial<OcrProfile> = {}): OcrProfile {
  return {
    id,
    name,
    enabled: true,
    revisionId: `${id}-r1`,
    sequence: 1,
    provider: 'OPENAI_COMPATIBLE',
    endpoint: 'https://example.test/v1',
    scope: 'EXTERNAL',
    model: 'vision-model',
    contextWindow: 128_000,
    maxOutputTokens: 4_096,
    inputPricePerMillion: 0.5,
    outputPricePerMillion: 1.5,
    apiKeyEnvironmentVariable: 'OCR_API_KEY',
    keyAvailable: true,
    imageCapabilityMeasured: null,
    imageCapabilityCheckedAt: null,
    ...over,
  };
}

function profileItem(name: string): HTMLElement {
  return screen.getByRole('listitem', { name });
}

describe('OCR profiles panel', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.listLlmPresets.mockResolvedValue([]);
  });

  afterEach(cleanup);

  it('lists profiles with scope, image capability and key presence', async () => {
    api.listOcrProfiles.mockResolvedValue([
      profile('p1', 'Cloud reader', {
        imageCapabilityMeasured: true,
        imageCapabilityCheckedAt: '2026-10-01T10:00:00Z',
      }),
      profile('p2', 'Local reader', {
        scope: 'LOCAL',
        endpoint: 'http://127.0.0.1:1234/v1',
        apiKeyEnvironmentVariable: null,
        keyAvailable: false,
        imageCapabilityMeasured: false,
        imageCapabilityCheckedAt: '2026-10-02T10:00:00Z',
      }),
      profile('p3', 'Missing key', { keyAvailable: false, enabled: false }),
    ]);

    render(OcrProfilesPanel);

    const cloud = await screen.findByRole('listitem', { name: 'Cloud reader' });
    expect(within(cloud).getByText('External')).toBeDefined();
    expect(within(cloud).getByText('Image support measured')).toBeDefined();
    expect(within(cloud).getByText(/2026-10-01T10:00:00Z/)).toBeDefined();
    expect(within(cloud).getByText(/OCR_API_KEY.*present/)).toBeDefined();

    const local = profileItem('Local reader');
    expect(within(local).getByText('Local')).toBeDefined();
    expect(within(local).getByText('Image support failed the check')).toBeDefined();
    expect(within(local).getByText('No key configured')).toBeDefined();

    const missing = profileItem('Missing key');
    expect(within(missing).getByText('Image support not measured')).toBeDefined();
    expect(within(missing).getByText(/OCR_API_KEY.*missing/)).toBeDefined();
    expect(within(missing).getByText('Disabled')).toBeDefined();
  });

  it('shows a load failure instead of an empty list and can retry', async () => {
    api.listOcrProfiles.mockRejectedValueOnce(new Error('the server is unreachable'));
    api.listOcrProfiles.mockResolvedValueOnce([profile('p1', 'Cloud reader')]);

    render(OcrProfilesPanel);

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('the server is unreachable');
    expect(screen.queryByText(/No OCR profiles/)).toBeNull();
    expect(screen.queryByRole('list', { name: 'OCR profiles' })).toBeNull();

    await fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByRole('listitem', { name: 'Cloud reader' })).toBeDefined();
  });

  it('renders HTML-like names, models and error text literally', async () => {
    const hostile = '<img src=x onerror="alert(1)">';
    api.listOcrProfiles.mockResolvedValue([
      profile('p1', hostile, { model: '<script>boom()</script>' }),
    ]);
    api.probeOcrProfile.mockRejectedValue(new Error('<b>bad</b> response'));

    const { container } = render(OcrProfilesPanel);

    const item = await screen.findByRole('listitem', { name: hostile });
    expect(within(item).getByText('<script>boom()</script>', { exact: false })).toBeDefined();
    await fireEvent.click(within(item).getByRole('button', { name: `Edit ${hostile}` }));
    await fireEvent.click(screen.getByRole('button', { name: 'Check image support' }));

    expect((await screen.findByRole('alert')).textContent).toContain('<b>bad</b> response');
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('script')).toBeNull();
    expect(container.querySelector('b')).toBeNull();
  });

  it('creates a profile and then edits it', async () => {
    api.listOcrProfiles.mockResolvedValue([]);
    api.createOcrProfile.mockImplementation(async (input) => profile('p1', input.name, {
      model: input.model,
      apiKeyEnvironmentVariable: input.apiKeyEnvironmentVariable,
    }));
    api.updateOcrProfile.mockImplementation(async (id, input) => profile(id, input.name, {
      model: input.model,
      sequence: 2,
      revisionId: 'p1-r2',
    }));

    render(OcrProfilesPanel);

    expect(await screen.findByText('No OCR profiles yet.')).toBeDefined();
    await fireEvent.click(screen.getByRole('button', { name: 'New profile' }));
    await fireEvent.input(screen.getByLabelText('Name'), { target: { value: 'Reader' } });
    await fireEvent.input(screen.getByLabelText('Model'), { target: { value: 'vision-1' } });
    await fireEvent.input(screen.getByLabelText('API key environment variable'), {
      target: { value: 'OCR_API_KEY' },
    });
    await fireEvent.click(screen.getByRole('button', { name: 'Create profile' }));

    expect(api.createOcrProfile).toHaveBeenCalledTimes(1);
    expect(api.createOcrProfile).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Reader',
      model: 'vision-1',
      provider: 'OPENAI_COMPATIBLE',
      enabled: true,
      apiKeyEnvironmentVariable: 'OCR_API_KEY',
    }));
    expect(await screen.findByRole('listitem', { name: 'Reader' })).toBeDefined();

    await fireEvent.click(screen.getByRole('button', { name: 'Edit Reader' }));
    await fireEvent.input(screen.getByLabelText('Model'), { target: { value: 'vision-2' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    expect(api.updateOcrProfile).toHaveBeenCalledWith('p1', expect.objectContaining({
      name: 'Reader',
      model: 'vision-2',
    }), 'p1-r1');
    expect(await screen.findByText(/'Reader' saved/)).toBeDefined();
  });

  it('shows the backend validation message and keeps the form', async () => {
    api.listOcrProfiles.mockResolvedValue([]);
    api.createOcrProfile.mockRejectedValue(
      await apiError('BAD_REQUEST', 'OCR profile fields are invalid', 400),
    );

    render(OcrProfilesPanel);
    await screen.findByText('No OCR profiles yet.');
    await fireEvent.click(screen.getByRole('button', { name: 'New profile' }));
    await fireEvent.input(screen.getByLabelText('Name'), { target: { value: 'Reader' } });
    await fireEvent.input(screen.getByLabelText('Model'), { target: { value: 'vision-1' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Create profile' }));

    expect((await screen.findByRole('alert')).textContent).toContain('OCR profile fields are invalid');
    expect((screen.getByLabelText('Name') as HTMLInputElement).value).toBe('Reader');
  });

  it('requires a name and a model before calling the server', async () => {
    api.listOcrProfiles.mockResolvedValue([]);

    render(OcrProfilesPanel);
    await screen.findByText('No OCR profiles yet.');
    await fireEvent.click(screen.getByRole('button', { name: 'New profile' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Create profile' }));

    expect((await screen.findByRole('alert')).textContent).toContain('Name and model are required');
    expect(api.createOcrProfile).not.toHaveBeenCalled();
  });

  it('keeps unsaved input on a 409 conflict, explains it and offers a reload', async () => {
    api.listOcrProfiles.mockResolvedValueOnce([profile('p1', 'Reader')]);
    api.updateOcrProfile.mockRejectedValue(
      await apiError('DUPLICATE_OCR_PROFILE_NAME', 'an OCR profile with that name already exists', 409),
    );

    render(OcrProfilesPanel);
    await fireEvent.click(await screen.findByRole('button', { name: 'Edit Reader' }));
    await fireEvent.input(screen.getByLabelText('Model'), { target: { value: 'my-unsaved-model' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('conflicts with the saved profiles');
    expect(alert.textContent).toContain('an OCR profile with that name already exists');
    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('my-unsaved-model');

    api.listOcrProfiles.mockResolvedValueOnce([profile('p1', 'Reader', { model: 'changed-elsewhere', sequence: 2 })]);
    await fireEvent.click(within(alert).getByRole('button', { name: 'Reload profiles' }));

    expect(await screen.findByText(/changed-elsewhere/)).toBeDefined();
    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('my-unsaved-model');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('sends the revision the form was loaded from on an edit, and never on create', async () => {
    api.listOcrProfiles.mockResolvedValueOnce([profile('p1', 'Reader', { revisionId: 'p1-r7' })]);
    api.updateOcrProfile.mockResolvedValue(profile('p1', 'Reader', { revisionId: 'p1-r8' }));
    api.createOcrProfile.mockResolvedValue(profile('p2', 'Other'));

    render(OcrProfilesPanel);
    await fireEvent.click(await screen.findByRole('button', { name: 'Edit Reader' }));
    await fireEvent.input(screen.getByLabelText('Model'), { target: { value: 'next-model' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await act(async () => {});

    expect(api.updateOcrProfile).toHaveBeenCalledTimes(1);
    expect(api.updateOcrProfile.mock.calls[0][0]).toBe('p1');
    expect(api.updateOcrProfile.mock.calls[0][2]).toBe('p1-r7');
    expect(api.updateOcrProfile.mock.calls[0][1]).not.toHaveProperty('expectedRevisionId');

    await fireEvent.click(screen.getByRole('button', { name: 'New profile' }));
    await fireEvent.input(screen.getByLabelText('Name'), { target: { value: 'Other' } });
    await fireEvent.input(screen.getByLabelText('Model'), { target: { value: 'm' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Create profile' }));
    await act(async () => {});
    expect(api.createOcrProfile).toHaveBeenCalledTimes(1);
    expect(api.createOcrProfile.mock.calls[0]).toHaveLength(1);
  });

  it('uses the newly saved revision for the next edit', async () => {
    api.listOcrProfiles.mockResolvedValueOnce([profile('p1', 'Reader', { revisionId: 'p1-r7' })]);
    api.updateOcrProfile.mockResolvedValue(profile('p1', 'Reader', { revisionId: 'p1-r8' }));

    render(OcrProfilesPanel);
    await fireEvent.click(await screen.findByRole('button', { name: 'Edit Reader' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await act(async () => {});
    await fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await act(async () => {});

    expect(api.updateOcrProfile.mock.calls.map((call) => call[2])).toEqual(['p1-r7', 'p1-r8']);
  });

  it('says a profile was changed elsewhere on a stale revision, keeps the input and offers both choices', async () => {
    api.listOcrProfiles.mockResolvedValueOnce([profile('p1', 'Reader', { revisionId: 'p1-r1' })]);
    api.updateOcrProfile.mockRejectedValue(
      await apiError('STALE_OCR_PROFILE_REVISION', 'the profile is at revision p1-r2', 409),
    );

    render(OcrProfilesPanel);
    await fireEvent.click(await screen.findByRole('button', { name: 'Edit Reader' }));
    await fireEvent.input(screen.getByLabelText('Model'), { target: { value: 'my-unsaved-model' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));

    const alert = await screen.findByRole('alert');
    expect(alert.textContent).toContain('This profile was changed elsewhere');
    expect(alert.textContent).toContain('nothing was saved');
    expect(alert.textContent).not.toContain('conflicts with the saved profiles');
    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('my-unsaved-model');
    expect(within(alert).getByRole('button', { name: 'Reload latest' })).toBeTruthy();
    expect(within(alert).getByRole('button', { name: 'Discard my edits and load latest' })).toBeTruthy();
  });

  it('Reload latest shows what changed but keeps the draft, and a retry then carries the new revision', async () => {
    api.listOcrProfiles.mockResolvedValueOnce([profile('p1', 'Reader', { revisionId: 'p1-r1' })]);
    api.updateOcrProfile
      .mockRejectedValueOnce(await apiError('STALE_OCR_PROFILE_REVISION', 'stale', 409))
      .mockResolvedValueOnce(profile('p1', 'Reader', { revisionId: 'p1-r3', model: 'my-unsaved-model' }));

    render(OcrProfilesPanel);
    await fireEvent.click(await screen.findByRole('button', { name: 'Edit Reader' }));
    await fireEvent.input(screen.getByLabelText('Model'), { target: { value: 'my-unsaved-model' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    const alert = await screen.findByRole('alert');

    api.listOcrProfiles.mockResolvedValueOnce([
      profile('p1', 'Reader', { revisionId: 'p1-r2', model: 'changed-elsewhere' }),
    ]);
    await fireEvent.click(within(alert).getByRole('button', { name: 'Reload latest' }));
    await act(async () => {});

    expect(screen.getByRole('listitem', { name: 'Reader' }).textContent).toContain('changed-elsewhere');
    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('my-unsaved-model');
    expect(screen.getByText(/saved profile now has model changed-elsewhere/i)).toBeTruthy();

    await fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    await act(async () => {});
    expect(api.updateOcrProfile.mock.calls[1][2]).toBe('p1-r2');
  });

  it('Discard my edits and load latest replaces the draft with the latest saved profile', async () => {
    api.listOcrProfiles.mockResolvedValueOnce([profile('p1', 'Reader', { revisionId: 'p1-r1' })]);
    api.updateOcrProfile.mockRejectedValue(await apiError('STALE_OCR_PROFILE_REVISION', 'stale', 409));

    render(OcrProfilesPanel);
    await fireEvent.click(await screen.findByRole('button', { name: 'Edit Reader' }));
    await fireEvent.input(screen.getByLabelText('Model'), { target: { value: 'my-unsaved-model' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    const alert = await screen.findByRole('alert');

    api.listOcrProfiles.mockResolvedValueOnce([
      profile('p1', 'Reader', { revisionId: 'p1-r2', model: 'changed-elsewhere' }),
    ]);
    await fireEvent.click(within(alert).getByRole('button', { name: 'Discard my edits and load latest' }));
    await act(async () => {});

    expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('changed-elsewhere');
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('runs one image check at a time and shows its result', async () => {
    api.listOcrProfiles.mockResolvedValue([profile('p1', 'Reader')]);
    let finish: (probe: OcrProfileProbe) => void = () => undefined;
    api.probeOcrProfile.mockImplementation(
      () => new Promise<OcrProfileProbe>((resolve) => { finish = resolve; }),
    );

    render(OcrProfilesPanel);
    await fireEvent.click(await screen.findByRole('button', { name: 'Edit Reader' }));

    const button = screen.getByRole('button', { name: 'Check image support' }) as HTMLButtonElement;
    await fireEvent.click(button);
    await fireEvent.click(button);

    expect(api.probeOcrProfile).toHaveBeenCalledTimes(1);
    expect(api.probeOcrProfile).toHaveBeenCalledWith('p1');
    const running = screen.getByRole('button', { name: 'Checking…' }) as HTMLButtonElement;
    expect(running.disabled).toBe(true);

    finish({
      profile: profile('p1', 'Reader', {
        imageCapabilityMeasured: true,
        imageCapabilityCheckedAt: '2026-10-07T08:00:00Z',
      }),
      supported: true,
      modelVersion: 'vision-model-2026',
      errorCode: null,
    });
    await act(async () => {});

    expect(await screen.findByText(/Image check passed/)).toBeDefined();
    expect(screen.getByText(/vision-model-2026/)).toBeDefined();
    expect(within(profileItem('Reader')).getByText('Image support measured')).toBeDefined();
    expect((screen.getByRole('button', { name: 'Check image support' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('shows a probe that could not run and a probe that measured no support', async () => {
    api.listOcrProfiles.mockResolvedValue([profile('p1', 'Reader')]);
    api.probeOcrProfile.mockRejectedValueOnce(
      await apiError('EXTERNAL_DISPATCH_NOT_PERMITTED', 'external dispatch is not permitted yet', 409),
    );
    api.probeOcrProfile.mockResolvedValueOnce({
      profile: profile('p1', 'Reader', { imageCapabilityMeasured: false }),
      supported: false,
      modelVersion: null,
      errorCode: 'IMAGE_REJECTED',
    });

    render(OcrProfilesPanel);
    await fireEvent.click(await screen.findByRole('button', { name: 'Edit Reader' }));
    await fireEvent.click(screen.getByRole('button', { name: 'Check image support' }));

    expect((await screen.findByRole('alert')).textContent).toContain('external dispatch is not permitted yet');

    await fireEvent.click(screen.getByRole('button', { name: 'Check image support' }));
    expect(await screen.findByText(/did not accept the image.*IMAGE_REJECTED/)).toBeDefined();
    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('is operable from the keyboard and returns focus after save and cancel', async () => {
    api.listOcrProfiles.mockResolvedValue([profile('p1', 'Reader')]);
    api.updateOcrProfile.mockImplementation(async (id, input) => profile(id, input.name));

    render(OcrProfilesPanel);
    const edit = await screen.findByRole('button', { name: 'Edit Reader' });
    edit.focus();
    await fireEvent.click(edit);

    const heading = screen.getByRole('heading', { name: 'Edit profile' });
    expect(document.activeElement).toBe(heading);

    const form = screen.getByRole('form', { name: 'OCR profile' });
    for (const label of ['Name', 'Provider', 'Model', 'Endpoint (base URL)', 'API key environment variable']) {
      expect(within(form).getByLabelText(label)).toBeDefined();
    }
    expect(within(form).getByRole('checkbox', { name: 'Enabled' })).toBeDefined();

    await fireEvent.submit(form);
    await act(async () => {});
    expect(api.updateOcrProfile).toHaveBeenCalledTimes(1);
    expect(document.activeElement).toBe(screen.getByRole('heading', { name: 'Edit profile' }));

    await fireEvent.click(screen.getByRole('button', { name: 'New profile' }));
    const cancel = screen.getByRole('button', { name: 'Cancel' });
    await fireEvent.click(cancel);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'New profile' }));
  });

  it('disables a profile through its own action', async () => {
    api.listOcrProfiles.mockResolvedValueOnce([profile('p1', 'Reader')]);
    api.disableOcrProfile.mockResolvedValue(undefined);

    render(OcrProfilesPanel);
    await fireEvent.click(await screen.findByRole('button', { name: 'Edit Reader' }));
    api.listOcrProfiles.mockResolvedValueOnce([profile('p1', 'Reader', { enabled: false })]);
    await fireEvent.click(screen.getByRole('button', { name: 'Disable profile' }));

    expect(api.disableOcrProfile).toHaveBeenCalledWith('p1');
    expect(await within(profileItem('Reader')).findByText('Disabled')).toBeDefined();
  });

  it('reports the saved profile list to a host through its callback', async () => {
    const onProfilesChanged = vi.fn();
    api.listOcrProfiles.mockResolvedValue([profile('p1', 'Reader')]);

    render(OcrProfilesPanel, { props: { onProfilesChanged } });
    await screen.findByRole('listitem', { name: 'Reader' });

    expect(onProfilesChanged).toHaveBeenLastCalledWith([expect.objectContaining({ id: 'p1' })]);
  });

  describe('provider presets and the image-capable model catalog', () => {
    const PRESETS = [
      { id: 'OPENAI', label: 'OpenAI', provider: 'OPENAI_COMPATIBLE', endpoint: 'https://api.openai.com/v1', apiKeyEnvironmentVariable: 'OPENAI_API_KEY' },
      { id: 'ANTHROPIC', label: 'Anthropic', provider: 'ANTHROPIC', endpoint: 'https://api.anthropic.com', apiKeyEnvironmentVariable: 'ANTHROPIC_API_KEY' },
    ];

    function catalogModel(id: string, over: Record<string, unknown> = {}) {
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

    async function openNewForm() {
      api.listOcrProfiles.mockResolvedValue([]);
      api.listLlmPresets.mockResolvedValue(PRESETS);
      render(OcrProfilesPanel);
      await screen.findByText('No OCR profiles yet.');
      await fireEvent.click(screen.getByRole('button', { name: 'New profile' }));
    }

    it('offers the same provider presets as the LLM profile form and applies one', async () => {
      await openNewForm();

      const options = Array.from((screen.getByLabelText('Provider preset') as HTMLSelectElement).options)
        .map((option) => option.textContent);
      expect(options).toEqual(expect.arrayContaining(['OpenAI', 'Anthropic']));

      await fireEvent.change(screen.getByLabelText('Provider preset'), { target: { value: 'ANTHROPIC' } });

      expect((screen.getByLabelText('Provider') as HTMLSelectElement).value).toBe('ANTHROPIC');
      expect((screen.getByLabelText('Endpoint (base URL)') as HTMLInputElement).value).toBe('https://api.anthropic.com');
      expect((screen.getByLabelText('API key environment variable') as HTMLInputElement).value).toBe('ANTHROPIC_API_KEY');
    });

    it('asks the catalog for image-capable models only and leaves out a model stated to be text-only', async () => {
      await openNewForm();
      api.fetchLlmCatalog.mockResolvedValue({
        live: true,
        models: [
          catalogModel('vision-model', { imageInput: true }),
          catalogModel('text-model', { imageInput: false }),
        ],
      });

      await fireEvent.change(screen.getByLabelText('Provider preset'), { target: { value: 'OPENAI' } });
      await fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }));
      await screen.findByLabelText('Model catalog');

      expect(api.fetchLlmCatalog).toHaveBeenCalledWith('OPENAI_COMPATIBLE', 'https://api.openai.com/v1', 'OPENAI_API_KEY', true);
      expect(screen.getByRole('option', { name: 'vision-model' })).toBeDefined();
      expect(screen.queryByRole('option', { name: /text-model/ })).toBeNull();
    });

    it('labels a model whose image support the catalog does not state instead of hiding or assuming it', async () => {
      await openNewForm();
      api.fetchLlmCatalog.mockResolvedValue({
        live: true,
        models: [catalogModel('vision-model', { imageInput: true }), catalogModel('mystery-model')],
      });

      await fireEvent.change(screen.getByLabelText('Provider preset'), { target: { value: 'OPENAI' } });
      await fireEvent.click(screen.getByRole('button', { name: 'Fetch models' }));
      await screen.findByLabelText('Model catalog');

      expect(screen.getByRole('option', { name: 'mystery-model — image support unknown' })).toBeDefined();
      expect(screen.getByRole('option', { name: 'vision-model' })).toBeDefined();

      await fireEvent.change(screen.getByLabelText('Model catalog'), { target: { value: 'mystery-model' } });

      expect((screen.getByLabelText('Model') as HTMLInputElement).value).toBe('mystery-model');
      expect(screen.getByText(/Image support unknown for this model/)).toBeDefined();
    });

    it('does not ask for a cache-read price, which an OCR profile has none of', async () => {
      await openNewForm();

      expect(screen.queryByLabelText('Cache-read price (USD / 1M tokens)')).toBeNull();
    });
  });
});
