# InfoScry LLM Provider Presets and Model Catalog Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the operator pick an LLM provider preset and a model from that provider's fetched catalog, with context window, output limit, and prices prefilled.

**Architecture:** A resource file (`providers.json`) holds the presets and a curated model-metadata table. A new server-side `LlmModelCatalog` fetches a provider's `/models` list, normalizes it (live OpenRouter metadata, curated table for OpenAI/Anthropic, local/free for a loopback endpoint), and falls back to the preset's static list on failure. Two read-only routes expose the presets and the normalized catalog; the Admin panel consumes them. No schema or profile-API change.

**Tech Stack:** Kotlin/JVM 25, Ktor server + Ktor CIO client, kotlinx.serialization, SvelteKit/TypeScript, Vitest.

**Spec:** [docs/superpowers/specs/2026-09-26-infoscry-llm-model-catalog-design.md](../specs/2026-09-26-infoscry-llm-model-catalog-design.md)

## Global Constraints

- Kotlin/JVM 25; one Gradle backend module; SvelteKit static frontend. Do not add dependencies.
- `llm_profiles` schema, its migrations, and the profile CRUD API are unchanged. Prices stay `NOT NULL >= 0`; `provider` stays `CHECK (provider IN ('OPENAI_COMPATIBLE','ANTHROPIC'))`.
- The API-key value never reaches the browser, a response body, or a log line. Only the environment-variable **name** crosses the request boundary; the server reads the value with `System::getenv`.
- Prices are USD per 1,000,000 tokens. A live per-token price is multiplied by 1,000,000.
- The catalog `endpoint` query parameter must be an absolute `http`/`https` URL.
- Read-only routes need no CSRF/bearer credential; the request guard gates mutations only.
- Product copy is English.
- Tests never call a real provider: use `FakeOpenAiServer` (`src/test/kotlin/infoscry/llm/FakeOpenAiServer.kt`).

## Review Focus

- A provider answers with a non-JSON body (an HTML proxy error page): the catalog must fall back, not throw.
- The endpoint carries a trailing slash or a path: the `/models` URL must stay well-formed.
- A provider hangs: the 5-second timeout must yield the fallback and return the route promptly.
- A curated model has a null price: `priceKnown` is false and the field is omitted, so the UI leaves it untouched.
- A model entry has a non-string or missing `id`: it is skipped, and the rest of the list still returns.

---

### Task 1: Provider preset catalog

**Files:**
- Create: `src/main/resources/llm/providers.json`
- Create: `src/main/kotlin/infoscry/llm/ProviderCatalog.kt`
- Test: `src/test/kotlin/infoscry/llm/ProviderCatalogTest.kt`

**Interfaces:**
- Consumes: `infoscry.llm.LlmProvider` (existing enum, `@Serializable`).
- Produces:
  - `@Serializable data class ProviderPreset(id: String, label: String, provider: LlmProvider, endpoint: String, apiKeyEnvironmentVariable: String? = null, staticModels: List<String> = emptyList())`
  - `@Serializable data class KnownModel(provider: LlmProvider, contextWindow: Int? = null, maxOutputTokens: Int? = null, inputPricePerMillion: Double? = null, outputPricePerMillion: Double? = null, cacheReadPricePerMillion: Double? = null)`
  - `@Serializable data class ProviderCatalogData(presets: List<ProviderPreset>, knownModels: Map<String, KnownModel> = emptyMap())`
  - `object ProviderCatalog { fun load(): ProviderCatalogData; fun presetFor(data: ProviderCatalogData, endpoint: String): ProviderPreset? }`

- [ ] **Step 1: Write the failing test**

```kotlin
package infoscry.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ProviderCatalogTest {
    @Test fun `loads the five presets with their endpoints and key variables`() {
        val data = ProviderCatalog.load()
        assertEquals(listOf("OPENAI", "ANTHROPIC", "OLLAMA", "OPENROUTER", "CUSTOM"), data.presets.map { it.id })
        val openai = data.presets.first { it.id == "OPENAI" }
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, openai.provider)
        assertEquals("https://api.openai.com/v1", openai.endpoint)
        assertEquals("OPENAI_API_KEY", openai.apiKeyEnvironmentVariable)
        assertEquals("https://api.anthropic.com", data.presets.first { it.id == "ANTHROPIC" }.endpoint)
        assertEquals(LlmProvider.ANTHROPIC, data.presets.first { it.id == "ANTHROPIC" }.provider)
        assertTrue(data.presets.first { it.id == "OLLAMA" }.apiKeyEnvironmentVariable == null)
    }

    @Test fun `known models carry provider and context`() {
        val data = ProviderCatalog.load()
        val gpt4o = assertNotNull(data.knownModels["gpt-4o"])
        assertEquals(LlmProvider.OPENAI_COMPATIBLE, gpt4o.provider)
        assertEquals(128_000, gpt4o.contextWindow)
        assertEquals(LlmProvider.ANTHROPIC, assertNotNull(data.knownModels["claude-opus-4-8"]).provider)
    }

    @Test fun `presetFor matches an endpoint exactly and returns null otherwise`() {
        val data = ProviderCatalog.load()
        assertEquals("OLLAMA", ProviderCatalog.presetFor(data, "http://localhost:11434/v1")?.id)
        assertEquals("OPENAI", ProviderCatalog.presetFor(data, "https://api.openai.com/v1")?.id)
        assertEquals(null, ProviderCatalog.presetFor(data, "https://unknown.example/v1"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "infoscry.llm.ProviderCatalogTest" -x frontendInstall -x frontendBuild -x frontendTest`
Expected: FAIL to compile — unresolved reference `ProviderCatalog`.

- [ ] **Step 3: Create `providers.json` and `ProviderCatalog.kt`**

`src/main/resources/llm/providers.json` — the exact object from the spec's §3, with the five presets and a `knownModels` entry for every id in the presets' `staticModels` lists (OpenAI and Anthropic). Use the spec's values for `gpt-4o` (`128000`/`16384`/`2.5`/`10.0`/`1.25`) and `claude-opus-4-8` (`200000`/`32000`/`15.0`/`75.0`/`1.5`); fill the remaining entries from each provider's published pricing, marking an unknown price by omitting the field.

`ProviderCatalog.kt`: the data classes above plus `load()` reading the classpath resource `/llm/providers.json` through a private `Json { ignoreUnknownKeys = true }` and throwing a clear `IllegalStateException` if the resource is missing or unparsable; `presetFor` returns `presets.firstOrNull { it.endpoint == endpoint }`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "infoscry.llm.ProviderCatalogTest" -x frontendInstall -x frontendBuild -x frontendTest`
Expected: PASS (3 tests).

- [ ] **Step 5: Commit**

```bash
git add src/main/resources/llm/providers.json src/main/kotlin/infoscry/llm/ProviderCatalog.kt src/test/kotlin/infoscry/llm/ProviderCatalogTest.kt
git commit -m "feat(llm): add provider preset catalog resource"
```

---

### Task 2: Model catalog service

**Files:**
- Create: `src/main/kotlin/infoscry/llm/LlmModelCatalog.kt`
- Test: `src/test/kotlin/infoscry/llm/LlmModelCatalogTest.kt`

**Interfaces:**
- Consumes: `ProviderCatalogData`, `ProviderPreset`, `KnownModel`, `ProviderCatalog.load()` (Task 1); `FakeOpenAiServer`, `FakeOpenAiResponse` (existing test helpers).
- Produces:
  - `@Serializable data class CatalogModel(id: String, contextWindow: Int? = null, maxOutputTokens: Int? = null, inputPricePerMillion: Double? = null, outputPricePerMillion: Double? = null, cacheReadPricePerMillion: Double? = null, priceKnown: Boolean = false)`
  - `@Serializable data class CatalogResponse(live: Boolean, models: List<CatalogModel>)`
  - `class LlmModelCatalog(catalog: ProviderCatalogData = ProviderCatalog.load(), httpClient: HttpClient = HttpClient(CIO), lookup: (String) -> String? = System::getenv, requestTimeoutMillis: Long = 5_000)`
  - `suspend fun fetch(provider: LlmProvider, endpoint: String, apiKeyEnvironmentVariable: String?): CatalogResponse`
  - `internal fun modelsUrl(provider: LlmProvider, endpoint: String): String`
  - `internal fun normalize(provider: LlmProvider, endpoint: String, entries: List<JsonObject>): List<CatalogModel>`
  - `internal fun fallback(provider: LlmProvider, endpoint: String): List<CatalogModel>`

- [ ] **Step 1: Write the failing normalize tests**

```kotlin
// LlmModelCatalogTest.kt (excerpt)
private val catalog = LlmModelCatalog()
private val router = Json.parseToJsonElement("""
  {"data":[{"id":"openai/gpt-4o","context_length":128000,
    "top_provider":{"max_completion_tokens":16384},
    "pricing":{"prompt":"0.0000025","completion":"0.00001","input_cache_read":"0.00000125"}}]}
""").jsonObject["data"]!!.jsonArray.toList()

@Test fun `openrouter live metadata scales per-token prices to per-million`() {
    val model = catalog.normalize(LlmProvider.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", router).single()
    assertEquals("openai/gpt-4o", model.id)
    assertEquals(128_000, model.contextWindow)
    assertEquals(16_384, model.maxOutputTokens)
    assertEquals(2.5, model.inputPricePerMillion)
    assertEquals(10.0, model.outputPricePerMillion)
    assertEquals(1.25, model.cacheReadPricePerMillion)
    assertTrue(model.priceKnown)
}

@Test fun `a curated id fills settings and prices from the table`() {
    val model = catalog.normalize(LlmProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1",
        listOf(Json.parseToJsonElement("""{"id":"gpt-4o"}""").jsonObject)).single()
    assertEquals(128_000, model.contextWindow)
    assertEquals(2.5, model.inputPricePerMillion)
    assertTrue(model.priceKnown)
}

@Test fun `an unknown id yields no fields and priceKnown false`() {
    val model = catalog.normalize(LlmProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1",
        listOf(Json.parseToJsonElement("""{"id":"gpt-99-unknown"}""").jsonObject)).single()
    assertEquals(null, model.contextWindow)
    assertEquals(null, model.inputPricePerMillion)
    assertFalse(model.priceKnown)
}

@Test fun `a loopback endpoint is local and free`() {
    val model = catalog.normalize(LlmProvider.OPENAI_COMPATIBLE, "http://localhost:11434/v1",
        listOf(Json.parseToJsonElement("""{"id":"gemma3:12b"}""").jsonObject)).single()
    assertEquals(0.0, model.inputPricePerMillion)
    assertEquals(0.0, model.outputPricePerMillion)
    assertTrue(model.priceKnown)
}

@Test fun `an entry without a string id is skipped`() {
    val entries = listOf(
        Json.parseToJsonElement("""{"missing":"id"}""").jsonObject,
        Json.parseToJsonElement("""{"id":"gpt-4o"}""").jsonObject,
    )
    assertEquals(listOf("gpt-4o"), catalog.normalize(LlmProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1", entries).map { it.id })
}

@Test fun `a curated entry with a null price is not priceKnown`() {
    val data = ProviderCatalogData(
        presets = emptyList(),
        knownModels = mapOf("gpt-partial" to KnownModel(provider = LlmProvider.OPENAI_COMPATIBLE, contextWindow = 8_000)),
    )
    val model = LlmModelCatalog(catalog = data).normalize(
        LlmProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1",
        listOf(Json.parseToJsonElement("""{"id":"gpt-partial"}""").jsonObject),
    ).single()
    assertEquals(8_000, model.contextWindow)
    assertEquals(null, model.inputPricePerMillion)
    assertFalse(model.priceKnown)
}

@Test fun `models url trims a trailing slash and picks the anthropic path`() {
    assertEquals("https://api.openai.com/v1/models", catalog.modelsUrl(LlmProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1/"))
    assertEquals("https://api.openai.com/v1/models", catalog.modelsUrl(LlmProvider.OPENAI_COMPATIBLE, "https://api.openai.com/v1"))
    assertEquals("https://api.anthropic.com/v1/models", catalog.modelsUrl(LlmProvider.ANTHROPIC, "https://api.anthropic.com"))
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "infoscry.llm.LlmModelCatalogTest" -x frontendInstall -x frontendBuild -x frontendTest`
Expected: FAIL to compile — unresolved `LlmModelCatalog`.

- [ ] **Step 3: Implement `normalize` and the data classes**

Per entry: read `id` (skip a non-string/missing one); read `context_length` as `Int`, `top_provider.max_completion_tokens` as `Int`, and the three `pricing` values as `String.toDouble()` scaled by 1e6. Overlay `knownModels[id]` when `known.provider == provider`. If the endpoint host is `localhost`, `127.0.0.1`, or `::1`, and no price was found, set all three prices to `0.0` and treat them as known. `priceKnown = inputPricePerMillion != null && outputPricePerMillion != null`. Return the list sorted by `id`.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "infoscry.llm.LlmModelCatalogTest" -x frontendInstall -x frontendBuild -x frontendTest`
Expected: PASS.

- [ ] **Step 5: Write the failing fetch tests**

```kotlin
@Test fun `fetch reads the provider list and sends the key from the environment`() = runBlocking {
    FakeOpenAiServer(listOf(FakeOpenAiResponse(statusCode = 200, body = """{"data":[{"id":"gpt-4o"}]}"""))).use { fake ->
        var seen: String? = null
        val service = LlmModelCatalog(lookup = { if (it == "TEST_KEY") "secret-value" else null })
        val result = service.fetch(LlmProvider.OPENAI_COMPATIBLE, fake.url, "TEST_KEY")
        seen = fake.authorization
        assertTrue(result.live)
        assertEquals(listOf("gpt-4o"), result.models.map { it.id })
        assertEquals("Bearer secret-value", seen)
    }
}

@Test fun `fetch falls back to the static list when the provider is unreachable`() = runBlocking {
    val result = LlmModelCatalog().fetch(LlmProvider.OPENAI_COMPATIBLE, "http://127.0.0.1:1", null)
    assertFalse(result.live)
    assertTrue(result.models.any { it.id == "gpt-4o" })
}

@Test fun `fetch falls back when the body is not JSON`() = runBlocking {
    FakeOpenAiServer(listOf(FakeOpenAiResponse(statusCode = 502, body = "<html>bad gateway</html>"))).use { fake ->
        val result = LlmModelCatalog().fetch(LlmProvider.OPENAI_COMPATIBLE, fake.url, null)
        assertFalse(result.live)
    }
}

@Test fun `fetch times out and falls back when the provider hangs`() = runBlocking {
    FakeOpenAiServer(listOf(FakeOpenAiResponse(statusCode = 200, body = """{"data":""", declaredLength = 4_096, holdMillis = 1_500))).use { fake ->
        val result = LlmModelCatalog(requestTimeoutMillis = 200).fetch(LlmProvider.OPENAI_COMPATIBLE, fake.url, null)
        assertFalse(result.live)
    }
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./gradlew test --tests "infoscry.llm.LlmModelCatalogTest" -x frontendInstall -x frontendBuild -x frontendTest`
Expected: FAIL — `fetch` is not defined.

- [ ] **Step 7: Implement `fetch` and `fallback`**

`fetch`: build the URL with `modelsUrl` — `"${endpoint.trimEnd('/')}/models"` for `OPENAI_COMPATIBLE`, `"${endpoint.trimEnd('/')}/v1/models"` for `ANTHROPIC`; send `Authorization: Bearer <key>` (OpenAI-compatible) or `x-api-key: <key>` + `anthropic-version: 2023-06-01` (Anthropic) when `lookup(apiKeyEnvironmentVariable)` is non-null. Wrap the call in `withTimeoutOrNull(requestTimeoutMillis)`; on timeout, any exception, a non-2xx status, or an unparsable body, return `CatalogResponse(live = false, models = fallback(...))`. On success, parse `data` and return `CatalogResponse(live = true, models = normalize(...))`.

`fallback`: `ProviderCatalog.presetFor(catalog, endpoint)?.staticModels` if present, otherwise the `knownModels` keys whose `provider == provider`; map each id through the same metadata overlay used by `normalize` (no live fields).

- [ ] **Step 8: Run test to verify it passes**

Run: `./gradlew test --tests "infoscry.llm.LlmModelCatalogTest" -x frontendInstall -x frontendBuild -x frontendTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/infoscry/llm/LlmModelCatalog.kt src/test/kotlin/infoscry/llm/LlmModelCatalogTest.kt
git commit -m "feat(llm): fetch and normalize provider model catalogs"
```

---

### Task 3: Catalog and preset routes

**Files:**
- Create: `src/main/kotlin/infoscry/server/LlmCatalogRoutes.kt`
- Modify: `src/main/kotlin/infoscry/server/Routes.kt` (add `configureLlmCatalogRoutes()` beside `configureLlmProfileRoutes(context)` at line 296)
- Test: `src/test/kotlin/infoscry/server/LlmCatalogRoutesTest.kt`

**Interfaces:**
- Consumes: `LlmModelCatalog`, `CatalogResponse` (Task 2); `ProviderCatalog`, `ProviderPreset` (Task 1); `infoscry.llm.ValidEnvironmentVariableName`; `call.handle`, `call.respondJson`, `BadRequestException` (existing).
- Produces:
  - `@Serializable data class LlmPresetApiView(id: String, label: String, provider: LlmProvider, endpoint: String, apiKeyEnvironmentVariable: String?)`
  - `@Serializable data class LlmPresetsResponse(presets: List<LlmPresetApiView>)`
  - `fun Routing.configureLlmCatalogRoutes(catalog: LlmModelCatalog = LlmModelCatalog(), providerCatalog: ProviderCatalogData = ProviderCatalog.load())`

- [ ] **Step 1: Write the failing presets test**

```kotlin
@Test fun `presets are listed without their static fallback lists`() = runBlocking {
    val response = harness.get("/api/llm/presets")
    val body = response.bodyAsText()
    assertEquals(HttpStatusCode.OK, response.status)
    assertContains(body, "\"id\":\"OPENAI\"")
    assertContains(body, "\"apiKeyEnvironmentVariable\":\"OPENAI_API_KEY\"")
    assertFalse(body.contains("staticModels"))
    assertEquals(5, Regex("\"id\":\"").findAll(body).count())
}
```

The test class mirrors `LlmProfileRoutesTest`: a `@BeforeTest` that creates a temporary data directory and `ApiTestServer(dataDir)`, and an `@AfterTest` that closes it.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew test --tests "infoscry.server.LlmCatalogRoutesTest" -x frontendInstall -x frontendBuild -x frontendTest`
Expected: FAIL — 404 (route absent).

- [ ] **Step 3: Implement the presets route and register it**

Map `providerCatalog.presets` to `LlmPresetApiView` (drop `staticModels`) and respond `200 LlmPresetsResponse`. Register `configureLlmCatalogRoutes()` in `configureRoutes` next to the profile routes.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew test --tests "infoscry.server.LlmCatalogRoutesTest" -x frontendInstall -x frontendBuild -x frontendTest`
Expected: PASS.

- [ ] **Step 5: Write the failing catalog tests**

```kotlin
@Test fun `catalog returns the provider list and authenticates with the named env var`() = runBlocking {
    FakeOpenAiServer(listOf(FakeOpenAiResponse(statusCode = 200, body = """{"data":[{"id":"gpt-4o"}]}"""))).use { fake ->
        val path = "/api/llm/catalog?provider=OPENAI_COMPATIBLE&endpoint=${fake.url}&apiKeyEnvironmentVariable=PATH"
        val response = harness.get(path)
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        assertContains(response.bodyAsText(), "\"id\":\"gpt-4o\"")
        assertEquals("Bearer ${System.getenv("PATH")}", fake.authorization)
    }
}

@Test fun `catalog falls back when the provider is unreachable`() = runBlocking {
    val response = harness.get("/api/llm/catalog?provider=OPENAI_COMPATIBLE&endpoint=http://127.0.0.1:1")
    assertEquals(HttpStatusCode.OK, response.status)
    assertContains(response.bodyAsText(), "\"live\":false")
}

@Test fun `invalid catalog requests are rejected without echoing input`() = runBlocking {
    assertEquals(HttpStatusCode.BadRequest, harness.get("/api/llm/catalog?provider=NOPE&endpoint=https://api.openai.com/v1").status)
    assertEquals(HttpStatusCode.BadRequest, harness.get("/api/llm/catalog?provider=OPENAI_COMPATIBLE&endpoint=not-a-url").status)
    assertEquals(HttpStatusCode.BadRequest, harness.get("/api/llm/catalog?provider=OPENAI_COMPATIBLE&endpoint=https://x/v1&apiKeyEnvironmentVariable=not a name").status)
}
```

- [ ] **Step 6: Run test to verify it fails**

Run: `./gradlew test --tests "infoscry.server.LlmCatalogRoutesTest" -x frontendInstall -x frontendBuild -x frontendTest`
Expected: FAIL — 404 (route absent).

- [ ] **Step 7: Implement the catalog route and validation**

Read the three query parameters. Validate: `provider` is `LlmProvider.entries.firstOrNull { it.name == raw }` else `BadRequestException("provider must be OPENAI_COMPATIBLE or ANTHROPIC")`; `endpoint` parses via `java.net.URI` with `isAbsolute` and a scheme in `setOf("http", "https")` else `BadRequestException("endpoint must be an absolute http or https URL")`; `apiKeyEnvironmentVariable`, when non-blank, passes `ValidEnvironmentVariableName.matches` else `BadRequestException`. Respond `200` with `catalog.fetch(...)`. Messages must not interpolate the caller's values.

- [ ] **Step 8: Run test to verify it passes**

Run: `./gradlew test --tests "infoscry.server.LlmCatalogRoutesTest" -x frontendInstall -x frontendBuild -x frontendTest`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add src/main/kotlin/infoscry/server/LlmCatalogRoutes.kt src/main/kotlin/infoscry/server/Routes.kt src/test/kotlin/infoscry/server/LlmCatalogRoutesTest.kt
git commit -m "feat(llm): expose provider presets and model catalog routes"
```

---

### Task 4: Admin panel preset and model picker

**Files:**
- Modify: `web/src/lib/api.ts`
- Modify: `web/src/lib/LlmAdminPanel.svelte`
- Test: `web/src/lib/LlmAdminPanel.test.ts`

**Interfaces:**
- Consumes: `GET /api/llm/presets`, `GET /api/llm/catalog` (Task 3); existing `LlmProvider`, `LlmProfileInput`.
- Produces:
  - `export type LlmPreset = { id: string; label: string; provider: LlmProvider; endpoint: string; apiKeyEnvironmentVariable: string | null }`
  - `export type LlmCatalogModel = { id: string; contextWindow: number | null; maxOutputTokens: number | null; inputPricePerMillion: number | null; outputPricePerMillion: number | null; cacheReadPricePerMillion: number | null; priceKnown: boolean }`
  - `export type LlmCatalog = { live: boolean; models: LlmCatalogModel[] }`
  - `export async function listLlmPresets(): Promise<LlmPreset[]>`
  - `export async function fetchLlmCatalog(provider: LlmProvider, endpoint: string, apiKeyEnvironmentVariable: string | null): Promise<LlmCatalog>`

- [ ] **Step 1: Write the failing Vitest cases**

Add to `LlmAdminPanel.test.ts`, extending the existing `vi.mock('./api')` with `listLlmPresets` and `fetchLlmCatalog`. Assert:
- choosing the preset labelled `OpenAI` sets the Endpoint input to `https://api.openai.com/v1` and the API-key-variable input to `OPENAI_API_KEY`;
- clicking `Fetch models` renders a `Model` select with the fetched id `gpt-4o` and calls `fetchLlmCatalog('OPENAI_COMPATIBLE', 'https://api.openai.com/v1', 'OPENAI_API_KEY')`;
- choosing a fetched model writes `128000` into Context window and `2.5` into the input price;
- a model with `priceKnown: false` shows the text `price unknown — enter manually`;
- when `fetchLlmCatalog` returns `{ live: false, models: [] }`, the panel shows `Could not fetch models — enter one manually.`.

- [ ] **Step 2: Run test to verify it fails**

Run: `cd web && npx vitest run src/lib/LlmAdminPanel.test.ts`
Expected: FAIL — `listLlmPresets`/`fetchLlmCatalog` not exported.

- [ ] **Step 3: Add the client functions to `api.ts`**

`listLlmPresets()` reads `{ presets }` from `/api/llm/presets` via `readJson`. `fetchLlmCatalog(provider, endpoint, apiKeyEnvironmentVariable)` builds a `URLSearchParams` with `provider`, `endpoint`, and — only when non-null — `apiKeyEnvironmentVariable`, then `readJson`s `/api/llm/catalog`. Both are plain GETs (no CSRF).

- [ ] **Step 4: Add the preset select, fetch action, and model select to `LlmAdminPanel.svelte`**

Load presets in `load()`. Add a `Provider preset` `<select>`; on change write `preset.provider`, `preset.endpoint`, `preset.apiKeyEnvironmentVariable` into `draft` (and `presetId`). Add a `Fetch models` button beside the model field; on success set `catalog` and `catalogLive`, on failure set the error text. Render a `Model` `<select>` when `catalog.length > 0`; selecting one copies every non-null field into `draft` and records whether `priceKnown` is false to show the hint. Keep every field editable; keep the existing save/delete/defaults behaviour.

- [ ] **Step 5: Run test to verify it passes**

Run: `cd web && npx vitest run src/lib/LlmAdminPanel.test.ts`
Expected: PASS.

- [ ] **Step 6: Run the frontend checks**

Run: `cd web && npm run check && npm test -- --run && npm run build`
Expected: 0 diagnostics; all tests pass; build succeeds.

- [ ] **Step 7: Commit**

```bash
git add web/src/lib/api.ts web/src/lib/LlmAdminPanel.svelte web/src/lib/LlmAdminPanel.test.ts
git commit -m "feat(web): choose provider presets and fetched models in the Admin view"
```

---

### Task 5: Status documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/implementation-status.md`

**Interfaces:**
- Consumes: nothing.

- [ ] **Step 1: Update the docs**

README: state that the Admin view offers provider presets (OpenAI, Anthropic, Ollama, OpenRouter) and fetches each provider's model list, prefilling context window, output limit, and prices where known; note that OpenAI/Anthropic prices come from the built-in table because their APIs return only ids. `implementation-status.md`: add one sentence to the reader/Admin description and note the two new read-only routes.

- [ ] **Step 2: Verify links and consistency**

Run: `grep -n "llm/catalog\|llm/presets\|preset" README.md docs/implementation-status.md`
Expected: the new wording is present; no broken relative link to the spec.

- [ ] **Step 3: Commit**

```bash
git add README.md docs/implementation-status.md
git commit -m "docs: record provider presets and model catalog"
```

---

## Verification

- Focused Kotlin: `./gradlew test --tests "infoscry.llm.ProviderCatalogTest" --tests "infoscry.llm.LlmModelCatalogTest" --tests "infoscry.server.LlmCatalogRoutesTest" -x frontendInstall -x frontendBuild -x frontendTest`
- Full backend gate: `./gradlew test -x frontendInstall -x frontendBuild -x frontendTest` (all Kotlin tests stay green; the profile tests especially, since `llm_profiles` and its API are untouched).
- Frontend: `cd web && npm run check && npm test -- --run && npm run build`
- Manual: start the server from a rebuilt `installDist`, open the Admin view, choose each preset, fetch models, and confirm the form fills where the spec says data is known.
