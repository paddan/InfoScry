import { act, cleanup, fireEvent, render, screen } from '@testing-library/svelte';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import CollectionOcrSettings from './CollectionOcrSettings.svelte';
import type { Collection, OcrLlmCandidate, OcrProfile } from './api';

const api = vi.hoisted(() => ({
  listOcrProfiles: vi.fn(),
  listOcrLlmCandidates: vi.fn(),
  copyLlmProfileToOcr: vi.fn(),
  updateCollectionOcrSettings: vi.fn(),
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
  listOcrLlmCandidates: api.listOcrLlmCandidates,
  copyLlmProfileToOcr: api.copyLlmProfileToOcr,
  updateCollectionOcrSettings: api.updateCollectionOcrSettings,
}));

async function apiError(code: string, message: string, status: number): Promise<Error> {
  const { ApiError } = await import('./api');
  return new ApiError(code, message, status);
}

function collection(name: string, over: Partial<Collection> = {}): Collection {
  return {
    id: name.toLowerCase(),
    name,
    ocrLanguages: 'eng',
    ocrEngine: 'TESSERACT',
    ocrImportMode: 'FILL_MISSING',
    ocrExternalPageLimit: 0,
    createdAt: '2026-09-21T07:00:00Z',
    updatedAt: '2026-09-21T07:00:00Z',
    lifecycle: 'ACTIVE',
    documentCount: 0,
    ...over,
  };
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
    imageCapabilityMeasured: true,
    imageCapabilityCheckedAt: '2026-09-21T07:00:00Z',
    ...over,
  };
}

const PROFILES = [
  profile('p-local', 'Local vision', { scope: 'LOCAL', apiKeyEnvironmentVariable: null, keyAvailable: false }),
  profile('p-cloud', 'Cloud vision', { keyAvailable: false }),
  profile('p-off', 'Retired vision', { enabled: false }),
];

function select(label: string): HTMLSelectElement {
  return screen.getByLabelText(label) as HTMLSelectElement;
}

async function renderSettings(target: Collection = collection('Nightfall'), onChanged = vi.fn()) {
  const view = render(CollectionOcrSettings, { collection: target, onChanged });
  await act(async () => {});
  return view;
}

describe('collection OCR settings', () => {
  beforeEach(() => {
    vi.resetAllMocks();
    api.listOcrProfiles.mockResolvedValue(PROFILES);
    api.listOcrLlmCandidates.mockResolvedValue([]);
  });

  afterEach(cleanup);

  it('shows the stored engine, import mode, profiles and external allowance', async () => {
    await renderSettings(collection('Nightfall', {
      ocrEngine: 'LLM',
      ocrImportMode: 'CHECK_AND_IMPROVE',
      ocrTranscriptionProfileId: 'p-cloud',
      ocrReviewProfileId: 'p-local',
      ocrExternalPageLimit: 25,
    }));

    expect(select('OCR engine').value).toBe('LLM');
    expect(select('Import mode').value).toBe('CHECK_AND_IMPROVE');
    expect(select('Transcription profile').value).toBe('p-cloud');
    expect(select('Review profile').value).toBe('p-local');
    expect((screen.getByLabelText('External page allowance') as HTMLInputElement).value).toBe('25');
  });

  it('keeps the defaults of a collection whose server predates these settings', async () => {
    const older = { ...collection('Nightfall') } as Partial<Collection>;
    delete older.ocrEngine;
    delete older.ocrImportMode;
    delete older.ocrExternalPageLimit;
    await renderSettings(older as Collection);

    expect(select('OCR engine').value).toBe('TESSERACT');
    expect(select('Import mode').value).toBe('FILL_MISSING');
    expect((screen.getByLabelText('External page allowance') as HTMLInputElement).value).toBe('0');
    expect(select('Transcription profile').value).toBe('');
  });

  it('offers only enabled profiles and says where each sends pages and whether its key is present', async () => {
    await renderSettings();

    const options = Array.from(select('Transcription profile').options).map((option) => option.textContent ?? '');
    expect(options.some((text) => text.includes('Local vision') && text.includes('local'))).toBe(true);
    expect(options.some((text) => text.includes('Cloud vision') && text.includes('external') && text.includes('key missing'))).toBe(true);
    expect(options.some((text) => text.includes('Retired vision'))).toBe(false);
  });

  it('saves the whole selection through the settings route and tells the person', async () => {
    api.updateCollectionOcrSettings.mockResolvedValue(collection('Nightfall', {
      ocrEngine: 'LLM',
      ocrImportMode: 'CHECK_AND_IMPROVE',
      ocrTranscriptionProfileId: 'p-cloud',
      ocrExternalPageLimit: 10,
    }));
    const onChanged = vi.fn();
    await renderSettings(collection('Nightfall'), onChanged);

    await fireEvent.change(select('OCR engine'), { target: { value: 'LLM' } });
    await fireEvent.change(select('Import mode'), { target: { value: 'CHECK_AND_IMPROVE' } });
    await fireEvent.change(select('Transcription profile'), { target: { value: 'p-cloud' } });
    await fireEvent.input(screen.getByLabelText('External page allowance'), { target: { value: '10' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR engine settings' }));
    await act(async () => {});

    expect(api.updateCollectionOcrSettings).toHaveBeenCalledTimes(1);
    expect(api.updateCollectionOcrSettings).toHaveBeenCalledWith('nightfall', {
      ocrEngine: 'LLM',
      ocrImportMode: 'CHECK_AND_IMPROVE',
      ocrTranscriptionProfileId: 'p-cloud',
      ocrReviewProfileId: '',
      ocrExternalPageLimit: 10,
    });
    expect(screen.getByRole('status').textContent).toContain('OCR engine settings saved');
    expect(onChanged).toHaveBeenCalledTimes(1);
  });

  it('clears the transcription profile when a local engine is chosen, because the server refuses both together', async () => {
    api.updateCollectionOcrSettings.mockResolvedValue(collection('Nightfall', { ocrEngine: 'SURYA' }));
    await renderSettings(collection('Nightfall', { ocrEngine: 'LLM', ocrTranscriptionProfileId: 'p-cloud' }));

    await fireEvent.change(select('OCR engine'), { target: { value: 'SURYA' } });
    expect(select('Transcription profile').disabled).toBe(true);
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR engine settings' }));
    await act(async () => {});

    expect(api.updateCollectionOcrSettings.mock.calls[0][1]).toMatchObject({
      ocrEngine: 'SURYA',
      ocrTranscriptionProfileId: '',
    });
  });

  it("shows the server's validation error and keeps what the person chose", async () => {
    api.updateCollectionOcrSettings.mockRejectedValue(
      await apiError('INVALID_REQUEST', 'engine LLM needs a transcription profile, and a local engine must not name one', 400),
    );
    await renderSettings();

    await fireEvent.change(select('OCR engine'), { target: { value: 'LLM' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR engine settings' }));
    await act(async () => {});

    expect(screen.getByRole('alert').textContent).toContain('engine LLM needs a transcription profile');
    expect(select('OCR engine').value).toBe('LLM');
    expect(screen.queryByText(/settings saved/)).toBeNull();
  });

  it('rejects an allowance that is not a whole number before sending anything', async () => {
    await renderSettings();

    await fireEvent.input(screen.getByLabelText('External page allowance'), { target: { value: '-3' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR engine settings' }));
    await act(async () => {});

    expect(api.updateCollectionOcrSettings).not.toHaveBeenCalled();
    expect(screen.getByRole('alert').textContent).toMatch(/whole number/i);
  });

  it('dispatches one save for duplicate clicks while it is pending', async () => {
    let finish!: (value: Collection) => void;
    api.updateCollectionOcrSettings.mockImplementation(() => new Promise<Collection>((resolve) => { finish = resolve; }));
    await renderSettings();

    const save = screen.getByRole('button', { name: 'Save OCR engine settings' });
    await fireEvent.click(save);
    await fireEvent.click(save);
    await fireEvent.submit(save.closest('form') as HTMLFormElement);

    expect(api.updateCollectionOcrSettings).toHaveBeenCalledTimes(1);
    expect((screen.getByRole('button', { name: 'Saving OCR engine settings…' }) as HTMLButtonElement).disabled).toBe(true);
    finish(collection('Nightfall'));
    await act(async () => {});
  });

  it('never lets a save of the previous collection write into the newly selected one', async () => {
    let finish!: (value: Collection) => void;
    api.updateCollectionOcrSettings.mockImplementation(() => new Promise<Collection>((resolve) => { finish = resolve; }));
    const onChanged = vi.fn();
    const view = await renderSettings(collection('Nightfall'), onChanged);

    await fireEvent.change(select('OCR engine'), { target: { value: 'SURYA' } });
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR engine settings' }));
    await view.rerender({
      collection: collection('Dawn', { ocrEngine: 'TESSERACT', ocrExternalPageLimit: 7 }),
      onChanged,
    });
    await act(async () => {});

    expect(select('OCR engine').value).toBe('TESSERACT');
    expect((screen.getByLabelText('External page allowance') as HTMLInputElement).value).toBe('7');

    finish(collection('Nightfall', { ocrEngine: 'SURYA' }));
    await act(async () => {});

    expect(select('OCR engine').value).toBe('TESSERACT');
    expect((screen.getByLabelText('External page allowance') as HTMLInputElement).value).toBe('7');
    expect(screen.queryByText(/settings saved/)).toBeNull();
    expect((screen.getByRole('button', { name: 'Save OCR engine settings' }) as HTMLButtonElement).disabled).toBe(false);
  });

  it('does not show a failed save of the previous collection against the new one', async () => {
    let fail!: (reason: unknown) => void;
    api.updateCollectionOcrSettings.mockImplementation(() => new Promise<Collection>((_, reject) => { fail = reject; }));
    const view = await renderSettings(collection('Nightfall'));

    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR engine settings' }));
    await view.rerender({ collection: collection('Dawn'), onChanged: vi.fn() });
    fail(await apiError('INVALID_REQUEST', 'nightfall refused', 400));
    await act(async () => {});

    expect(screen.queryByRole('alert')).toBeNull();
  });

  it('starts the draft of another collection from that collection, not from edits to the last one', async () => {
    const view = await renderSettings(collection('Nightfall'));

    await fireEvent.change(select('Import mode'), { target: { value: 'CHECK_AND_IMPROVE' } });
    await view.rerender({ collection: collection('Dawn'), onChanged: vi.fn() });
    await act(async () => {});

    expect(select('Import mode').value).toBe('FILL_MISSING');
  });

  it('renders profile names and errors that look like markup literally', async () => {
    const markup = '<img src=x onerror="alert(1)">';
    api.listOcrProfiles.mockResolvedValue([profile('p-x', markup)]);
    api.updateCollectionOcrSettings.mockRejectedValue(
      await apiError('INVALID_REQUEST', '<script>alert(2)</script> refused', 400),
    );
    const { container } = await renderSettings();

    expect(Array.from(select('Review profile').options).some((option) => (option.textContent ?? '').includes(markup))).toBe(true);
    await fireEvent.click(screen.getByRole('button', { name: 'Save OCR engine settings' }));
    await act(async () => {});

    expect(screen.getByRole('alert').textContent).toContain('<script>alert(2)</script> refused');
    expect(container.querySelector('img')).toBeNull();
    expect(container.querySelector('script')).toBeNull();
  });

  it('keeps a selected profile that is no longer selectable visible instead of silently dropping it', async () => {
    await renderSettings(collection('Nightfall', {
      ocrEngine: 'LLM',
      ocrTranscriptionProfileId: 'p-off',
    }));

    expect(select('Transcription profile').value).toBe('p-off');
    expect(screen.getByText(/no longer available/i)).toBeTruthy();
  });

  it('says so when the profiles cannot be listed and still lets local settings be edited', async () => {
    api.listOcrProfiles.mockRejectedValue(await apiError('INTERNAL_ERROR', 'profiles unavailable', 500));
    await renderSettings();

    expect(screen.getByRole('alert').textContent).toContain('profiles unavailable');
    expect(select('OCR engine').disabled).toBe(false);
  });

  describe('external page allowance hint', () => {
    const HINT = 'This collection sends pages to an external provider, but the allowance is 0, so imports and scans will pause until you approve pages or raise the allowance.';

    it('shows the hint when a stored external review profile meets an allowance of 0', async () => {
      await renderSettings(collection('Nightfall', { ocrReviewProfileId: 'p-cloud', ocrExternalPageLimit: 0 }));

      expect(screen.getByText(HINT)).toBeTruthy();
    });

    it('shows the hint when an external transcription profile is chosen for the LLM engine', async () => {
      await renderSettings(collection('Nightfall', {
        ocrEngine: 'LLM',
        ocrTranscriptionProfileId: 'p-cloud',
        ocrExternalPageLimit: 0,
      }));

      expect(screen.getByText(HINT)).toBeTruthy();
    });

    it('shows the hint for an external LLM profile selected in the draft', async () => {
      api.listOcrLlmCandidates.mockResolvedValue([
        {
          id: 'l-vision',
          name: 'Reader',
          provider: 'OPENAI_COMPATIBLE',
          endpoint: 'https://example.test/v1',
          scope: 'EXTERNAL',
          model: 'a-model',
          apiKeyEnvironmentVariable: 'LLM_KEY',
          keyAvailable: true,
          imageInput: true,
        },
      ]);
      await renderSettings();

      expect(screen.queryByText(HINT)).toBeNull();
      await fireEvent.change(select('Review profile'), { target: { value: 'llm:l-vision' } });

      expect(screen.getByText(HINT)).toBeTruthy();
    });

    it('hides the hint when the selected profiles are local', async () => {
      await renderSettings(collection('Nightfall', {
        ocrEngine: 'LLM',
        ocrTranscriptionProfileId: 'p-local',
        ocrReviewProfileId: 'p-local',
        ocrExternalPageLimit: 0,
      }));

      expect(screen.queryByText(HINT)).toBeNull();
    });

    it('hides the hint when the external allowance is 3', async () => {
      await renderSettings(collection('Nightfall', { ocrReviewProfileId: 'p-cloud', ocrExternalPageLimit: 3 }));

      expect(screen.queryByText(HINT)).toBeNull();
    });

    it('follows the allowance the person is typing, not only the stored value', async () => {
      await renderSettings(collection('Nightfall', { ocrReviewProfileId: 'p-cloud', ocrExternalPageLimit: 0 }));

      await fireEvent.input(screen.getByLabelText('External page allowance'), { target: { value: '2' } });
      expect(screen.queryByText(HINT)).toBeNull();
    });

    it('explains the allowance in help text that the field refers to', async () => {
      await renderSettings();

      const field = screen.getByLabelText('External page allowance');
      const note = document.getElementById(field.getAttribute('aria-describedby') ?? '');
      expect(note?.textContent).toContain(
        'Pages that may be sent to an external provider per import or scan, counted once each. 0 means every external page needs your approval.',
      );
    });
  });

  describe('LLM profiles with image input', () => {
    const LLM: OcrLlmCandidate[] = [
      candidate('l-vision', 'Reader', { imageInput: true }),
      candidate('l-unknown', 'Mystery', { imageInput: null, scope: 'LOCAL', apiKeyEnvironmentVariable: null, keyAvailable: false }),
      candidate('l-text', 'Chatter', { imageInput: false }),
    ];

    function candidate(id: string, name: string, over: Partial<OcrLlmCandidate> = {}): OcrLlmCandidate {
      return {
        id,
        name,
        provider: 'OPENAI_COMPATIBLE',
        endpoint: 'https://example.test/v1',
        scope: 'EXTERNAL',
        model: 'a-model',
        apiKeyEnvironmentVariable: 'LLM_KEY',
        keyAvailable: true,
        imageInput: null,
        ...over,
      };
    }

    function optionTexts(label: string, group: string): string[] {
      const groupElement = Array.from(select(label).querySelectorAll('optgroup'))
        .find((element) => element.label === group);
      return groupElement === undefined
        ? []
        : Array.from(groupElement.querySelectorAll('option')).map((option) => option.textContent ?? '');
    }

    it('lists OCR profiles and image-capable LLM profiles in two labelled groups, without text-only ones', async () => {
      api.listOcrLlmCandidates.mockResolvedValue(LLM);
      await renderSettings();

      for (const label of ['Transcription profile', 'Review profile']) {
        expect(optionTexts(label, 'OCR profiles').some((text) => text.includes('Local vision'))).toBe(true);
        const llm = optionTexts(label, 'LLM profiles with image input');
        expect(llm.some((text) => text.includes('Reader') && text.includes('key present'))).toBe(true);
        expect(llm.some((text) => text.includes('Mystery') && text.includes('image support unknown'))).toBe(true);
        expect(llm.some((text) => text.includes('Chatter'))).toBe(false);
      }
    });

    it('never calls an unstated model image capable and shows keys by presence only', async () => {
      api.listOcrLlmCandidates.mockResolvedValue(LLM);
      await renderSettings();

      const llm = optionTexts('Transcription profile', 'LLM profiles with image input');
      const reader = llm.find((text) => text.includes('Reader')) ?? '';
      const mystery = llm.find((text) => text.includes('Mystery')) ?? '';
      expect(reader).not.toContain('unknown');
      expect(mystery).toContain('image support unknown');
      expect(mystery).toContain('no key needed');
      expect(document.body.textContent).not.toContain('LLM_KEY=');
    });

    it('copies a chosen LLM profile into an OCR profile on save and selects that profile', async () => {
      api.listOcrLlmCandidates.mockResolvedValue(LLM);
      api.copyLlmProfileToOcr.mockResolvedValue(profile('copy-1', 'Reader (from LLM profile)'));
      api.updateCollectionOcrSettings.mockResolvedValue(collection('Nightfall'));
      await renderSettings();

      await fireEvent.change(select('OCR engine'), { target: { value: 'LLM' } });
      await fireEvent.change(select('Transcription profile'), { target: { value: 'llm:l-vision' } });
      await fireEvent.change(select('Review profile'), { target: { value: 'llm:l-vision' } });
      await fireEvent.click(screen.getByRole('button', { name: 'Save OCR engine settings' }));
      await act(async () => {});

      expect(api.copyLlmProfileToOcr).toHaveBeenCalledTimes(1);
      expect(api.copyLlmProfileToOcr).toHaveBeenCalledWith('l-vision');
      expect(api.updateCollectionOcrSettings.mock.calls[0][1]).toMatchObject({
        ocrTranscriptionProfileId: 'copy-1',
        ocrReviewProfileId: 'copy-1',
      });
    });

    it('saves nothing and says why when the server refuses a text-only LLM profile', async () => {
      api.listOcrLlmCandidates.mockResolvedValue(LLM);
      api.copyLlmProfileToOcr.mockRejectedValue(
        await apiError('LLM_PROFILE_TEXT_ONLY', 'the model of that LLM profile does not accept image input', 409),
      );
      await renderSettings();

      await fireEvent.change(select('OCR engine'), { target: { value: 'LLM' } });
      await fireEvent.change(select('Transcription profile'), { target: { value: 'llm:l-unknown' } });
      await fireEvent.click(screen.getByRole('button', { name: 'Save OCR engine settings' }));
      await act(async () => {});

      expect(api.updateCollectionOcrSettings).not.toHaveBeenCalled();
      expect(screen.getByRole('alert').textContent).toContain('does not accept image input');
    });

    it('shows a profile that is a copy of an LLM profile as that LLM profile, found by id and not by name', async () => {
      api.listOcrLlmCandidates.mockResolvedValue(LLM);
      api.listOcrProfiles.mockResolvedValue([
        ...PROFILES,
        profile('copy-1', 'Renamed long ago', { sourceLlmProfileId: 'l-vision' }),
        profile('same-name', 'Reader (from LLM profile)'),
      ]);
      await renderSettings(collection('Nightfall', { ocrEngine: 'LLM', ocrTranscriptionProfileId: 'copy-1' }));

      expect(select('Transcription profile').value).toBe('llm:l-vision');
      const ocrGroup = optionTexts('Transcription profile', 'OCR profiles');
      expect(ocrGroup.some((text) => text.includes('Renamed long ago'))).toBe(false);
      expect(ocrGroup.some((text) => text.includes('Reader (from LLM profile)'))).toBe(true);
    });

    it('shows the stored copy as its LLM profile when the OCR list arrives before the LLM list', async () => {
      let answerLlm: (value: OcrLlmCandidate[]) => void = () => {};
      api.listOcrLlmCandidates.mockReturnValue(new Promise((resolve) => { answerLlm = resolve; }));
      api.listOcrProfiles.mockResolvedValue([
        ...PROFILES,
        profile('copy-1', 'Vision LLM (from LLM profile)', { sourceLlmProfileId: 'l-vision' }),
      ]);
      render(CollectionOcrSettings, {
        collection: collection('Nightfall', { ocrEngine: 'LLM', ocrTranscriptionProfileId: 'copy-1' }),
        onChanged: vi.fn(),
      });
      await act(async () => {});
      answerLlm(LLM);
      await act(async () => {});

      expect(select('Transcription profile').value).toBe('llm:l-vision');
      expect(select('Transcription profile').selectedOptions[0].textContent).toContain('Reader');
      expect(screen.queryByText(/no longer available/)).toBeNull();
    });

    it('shows the stored copy as its LLM profile when the LLM list arrives before the OCR list', async () => {
      let answerProfiles: (value: OcrProfile[]) => void = () => {};
      api.listOcrProfiles.mockReturnValue(new Promise((resolve) => { answerProfiles = resolve; }));
      api.listOcrLlmCandidates.mockResolvedValue(LLM);
      render(CollectionOcrSettings, {
        collection: collection('Nightfall', { ocrEngine: 'LLM', ocrTranscriptionProfileId: 'copy-1' }),
        onChanged: vi.fn(),
      });
      await act(async () => {});
      answerProfiles([...PROFILES, profile('copy-1', 'Vision LLM (from LLM profile)', { sourceLlmProfileId: 'l-vision' })]);
      await act(async () => {});

      expect(select('Transcription profile').value).toBe('llm:l-vision');
      expect(select('Transcription profile').selectedOptions[0].textContent).toContain('Reader');
      expect(screen.queryByText(/no longer available/)).toBeNull();
    });

    it('explains an empty list and links to Admin OCR profiles instead of showing an unexplained None', async () => {
      api.listOcrProfiles.mockResolvedValue([]);
      api.listOcrLlmCandidates.mockResolvedValue([candidate('l-text', 'Chatter', { imageInput: false })]);
      const open = vi.fn();
      const view = render(CollectionOcrSettings, {
        collection: collection('Nightfall'),
        onChanged: vi.fn(),
        onOpenOcrProfiles: open,
      });
      await act(async () => {});

      expect(view.container.textContent).toContain('No OCR profile and no LLM profile with image input exists yet');
      await fireEvent.click(screen.getByRole('button', { name: 'Open Admin → OCR profiles' }));
      expect(open).toHaveBeenCalledTimes(1);
    });
  });
});
