# InfoScry LLM Provider Presets and Model Catalog

**Date:** 2026-09-26
**Status:** Approved design; implementation pending
**Product language:** English

**Scope:** This spec extends the main design's [§11 LLM configuration](2026-09-20-infoscry-design.md)
and the web Admin view. It adds provider presets and a server-side model-catalog
fetch, so an operator can pick a provider and one of its models from a list and
have the context window, output limit, and prices prefilled instead of typing
every field. It does not change the `LlmProfile` schema, the two provider
protocols, or the API-key boundary.

## 1. Context and relation to the main design

The main design (§11.1) fixes a profile's fields and states that only two
provider families are spoken: `OPENAI_COMPATIBLE` and `ANTHROPIC`. Today the
Admin view makes the operator type every field by hand.

Palmemordsarkivet's admin page offers named backends (Claude, OpenAI, DeepSeek,
OpenRouter, Ollama, custom) and fetches model **ids** from `{base_url}/models`,
but keeps settings and prices static/manual. This spec goes one step further: it
prefills settings and prices too, sourcing them live where a provider exposes
them and from a small built-in table otherwise.

The main design's out-of-scope statement about web Settings pages was already
superseded for the LLM Admin view by the approved admin page; this spec adds to
that one view only.

## 2. Non-goals

- No live pricing for OpenAI or Anthropic: their `/models` APIs return ids (and a
  display name), not prices. Those come from the built-in table.
- No automatic or background price refresh, and no third-party pricing feed.
- No third provider protocol; every preset maps to the existing
  `OPENAI_COMPATIBLE` or `ANTHROPIC`.
- No API-key value in the browser; only the environment-variable name is handled
  there.
- No schema change to `llm_profiles`; "price unknown" stays a UI concept and 0
  keeps its existing meaning (free or unreported).

## 3. Provider presets

A single inspectable resource, `src/main/resources/llm/providers.json`, carries
both the presets and the built-in model metadata. Keeping the data in a resource
(like `models/embedding-model.json`) lets a reader correct a price without
reading or recompiling Kotlin.

```json
{
  "presets": [
    { "id": "OPENAI",     "label": "OpenAI",                   "provider": "OPENAI_COMPATIBLE", "endpoint": "https://api.openai.com/v1",   "apiKeyEnvironmentVariable": "OPENAI_API_KEY",   "staticModels": ["gpt-5", "gpt-5-mini", "gpt-4.1", "gpt-4.1-mini", "gpt-4o", "gpt-4o-mini", "o3", "o3-pro", "o4-mini"] },
    { "id": "ANTHROPIC",  "label": "Anthropic",                "provider": "ANTHROPIC",         "endpoint": "https://api.anthropic.com",   "apiKeyEnvironmentVariable": "ANTHROPIC_API_KEY", "staticModels": ["claude-opus-4-8", "claude-opus-4-7", "claude-sonnet-4-6", "claude-haiku-4-5-20251001"] },
    { "id": "OLLAMA",     "label": "Ollama (local)",           "provider": "OPENAI_COMPATIBLE", "endpoint": "http://localhost:11434/v1",   "apiKeyEnvironmentVariable": null,               "staticModels": [] },
    { "id": "OPENROUTER", "label": "OpenRouter",               "provider": "OPENAI_COMPATIBLE", "endpoint": "https://openrouter.ai/api/v1", "apiKeyEnvironmentVariable": "OPENROUTER_API_KEY", "staticModels": ["openai/gpt-4o"] },
    { "id": "CUSTOM",     "label": "Custom OpenAI-compatible", "provider": "OPENAI_COMPATIBLE", "endpoint": "",                            "apiKeyEnvironmentVariable": null,               "staticModels": [] }
  ],
  "knownModels": {
    "gpt-4o":    { "provider": "OPENAI_COMPATIBLE", "contextWindow": 128000, "maxOutputTokens": 16384, "inputPricePerMillion": 2.5, "outputPricePerMillion": 10.0, "cacheReadPricePerMillion": 1.25 },
    "claude-opus-4-8": { "provider": "ANTHROPIC", "contextWindow": 200000, "maxOutputTokens": 32000, "inputPricePerMillion": 15.0, "outputPricePerMillion": 75.0, "cacheReadPricePerMillion": 1.5 }
  }
}
```

- `presets[].endpoint` is the value a profile must hold: the OpenAI-compatible
  client appends `/chat/completions`, the Anthropic client appends
  `/v1/messages`.
- `presets[].staticModels` is the offline fallback list for that preset (see §9);
  it is empty for `Ollama` and `CUSTOM`, where the operator types a model.
- `knownModels` is keyed by the exact model id a provider lists, and each entry
  names its `provider` so one table can hold both families. It supplies context
  window, output limit, and prices for OpenAI and Anthropic, whose `/models` APIs
  return no such data. The shipped set covers the models in the presets'
  `staticModels` lists; prices are taken from each provider's published pricing
  at implementation time and recorded here, and this file is the single place to
  correct them.
- A model id absent from `knownModels` yields unknown settings/prices, not
  guessed ones.

## 4. Model catalog service

A new backend service fetches a provider's model list and normalizes it to
profile-ready fields. It runs inside the server process and reads the API key
from the server environment; the browser never sends a key.

Fetch by provider family:

| Provider | Request | Auth |
| --- | --- | --- |
| `OPENAI_COMPATIBLE` | `GET {endpoint}/models` | `Authorization: Bearer <key>` when the env var is set and non-empty |
| `ANTHROPIC` | `GET {endpoint}/v1/models` | `x-api-key: <key>` and `anthropic-version: 2023-06-01` |

Normalization per model id:

1. **Live metadata first.** OpenRouter's `/models` response carries
   `context_length`, `top_provider.max_completion_tokens`, and `pricing`
   (`prompt`, `completion`, `input_cache_read`, USD per token). These are used
   directly; per-token prices are multiplied by 1,000,000.
2. **Built-in table next.** For OpenAI and Anthropic, values come from
   `knownModels[id]`.
3. **Otherwise unknown.** Omit the field. Ollama is treated as local/free: its
   prices are 0 and `priceKnown` is true.

`priceKnown` is true when every price came from live data or the table, and
false otherwise. `contextWindow` and `maxOutputTokens` are nullable when
unknown. The service never returns a guessed value.

## 5. HTTP API

A new read-only route, registered alongside the existing profile routes:

```text
GET /api/llm/catalog?provider=<OPENAI_COMPATIBLE|ANTHROPIC>&endpoint=<url>&apiKeyEnvironmentVariable=<name>
```

- Read-only, so the global guard's CSRF/bearer requirement does not apply and no
  credential is needed beyond the loopback `Host` boundary.
- Validation: `provider` must be a valid enum value; `endpoint` must be an
  absolute `http`/`https` URL; `apiKeyEnvironmentVariable` must be absent, empty,
  or a valid variable name. An invalid request is `400 INVALID_REQUEST` and must
  not echo caller-supplied values.
- Response:

```json
{
  "live": true,
  "models": [
    {
      "id": "openai/gpt-4o",
      "contextWindow": 128000,
      "maxOutputTokens": 16384,
      "inputPricePerMillion": 2.5,
      "outputPricePerMillion": 10.0,
      "cacheReadPricePerMillion": 1.25,
      "priceKnown": true
    }
  ]
}
```

- `live` is true when the list came from the provider and false when it fell back
  to the built-in table (see §9).

## 6. Web UI

In `LlmAdminPanel.svelte`:

- A **Provider preset** select bound to the preset ids. Choosing one writes the
  preset's `provider`, `endpoint`, and `apiKeyEnvironmentVariable` into the form.
  `CUSTOM` clears the endpoint and key variable. The raw provider, endpoint, and
  key-variable fields stay visible and editable.
- A **Fetch models** action beside the model field calls the catalog route with
  the form's current provider, endpoint, and key-variable name.
- The response fills a model select listing the returned ids. Selecting a model
  writes its id and every known field into the form; unknown fields are left
  untouched.
- When `priceKnown` is false, the form shows a "price unknown — enter manually"
  hint next to the prices. When the list is empty or offline, the UI says so and
  the model field remains free text.
- The catalog only prefills. Every field is still editable, and saving uses the
  unchanged profile API.

## 7. Prices, units, and unknown data

- Profile prices are USD per 1,000,000 tokens; live per-token prices are scaled
  accordingly.
- `llm_profiles` is unchanged: prices stay `NOT NULL >= 0`, so there is no
  migration. A model with unknown prices is saved with the operator's manual
  values (or 0), exactly as today.
- Cost display is unaffected by this spec. Unknown pricing therefore reads as
  `$0.0000` until the operator fills it in; the new hint is what tells them to.

## 8. Security and privacy

- The API key is read from `System::getenv` on the server. It never enters the
  request from the browser, the response, or any log line. Only the
  environment-variable **name** crosses the boundary.
- The catalog request's `endpoint` is restricted to absolute `http`/`https` URLs.
  The fetch is app-initiated and triggered only from the loopback-only Admin
  view, so the caller is the same local operator who configures endpoints by
  hand; the request guard already rejects non-loopback callers. This is recorded
  as an accepted local-use boundary rather than an SSRF defense.
- Errors and logs must not contain the key value or caller-supplied field values.
- No new persisted state: the catalog is fetched on demand and not written to
  SQLite.

## 9. Failure behavior

- If the provider is unreachable, times out (5 s), or answers non-2xx, the
  service returns the preset's `staticModels` list with `live: false`. Failure
  never yields an error page for a read action.
- A 2xx response with an unparsable body is treated the same as a failure.
- A model id with no metadata is still selectable; its settings and prices stay
  as the operator has them.

## 10. Testing

- **Unit:** normalization for OpenRouter (per-token → per-million, context,
  max output), the built-in merge for OpenAI/Anthropic, Ollama as free, and an
  unknown id (fields omitted, `priceKnown` false).
- **Route:** `GET /api/llm/catalog` against a fake provider (extend the existing
  `FakeOpenAiServer`), asserting the normalized body, the `live: false` fallback
  on failure, and that no key value appears in the response or in the request it
  received.
- **Frontend (Vitest):** selecting a preset fills provider/endpoint/key; a fetch
  populates the model list; selecting a model fills settings and prices; unknown
  prices show the hint.
- The existing backend and frontend suites stay green.

## 11. Acceptance

- From the Admin view, an operator creates an OpenAI, Anthropic, Ollama, and
  OpenRouter profile by choosing a preset, fetching models, and choosing one; the
  form is prefilled with correct context, output limit, and prices where the data
  is known, and the profile saves through the existing API.
- Killing the provider leaves the Admin view usable: the fetch reports offline
  and the operator can still type a model.
- No API-key value is observable in the browser, in the catalog response, or in
  server logs.
