# Task 3 / Task 21 — cited Ask

## Scope

Implemented only the Ask boundary: conservative full-request budgeting, diverse evidence packing, request-scoped citation validation, streaming Ask events, `/api/ask`, and `infoscry ask`. No Investigate, UI, release, schema, or controller-owned documentation changes were included in this commit.

## TDD evidence

- RED: the initial budget test failed because the provider-frame serializer used heterogeneous JSON maps and raised `SerializationException`; the test then failed correctly on the too-small context fixture.
- GREEN: after switching to typed provider-frame DTOs and correcting the fixture window, `RequestBudgetTest` passed.
- Additional tests cover duplicate collapse/document diversity, evidence delimiters, and unknown citation IDs.

## Verification

- `JAVA_HOME="$(asdf where java)" ./gradlew test --tests 'infoscry.ask.*' --tests infoscry.llm.RequestBudgetTest` — PASS.
- `git diff --check` — PASS.
- `JAVA_HOME="$(asdf where java)" ./gradlew check` — PASS, including frontend tests (5/5) and backend tests.

## Design/security decisions

`RequestBudget` serializes roles, escaped content, tools, required-tool metadata, framing, streaming, and output reserve. It uses UTF-8 bytes as an explicitly estimated fallback and never uses the E5 tokenizer. `AskService` calls `requireFits` immediately before the provider flow, so irreducible budget overflow makes no provider call. Evidence is wrapped in delimited source data and prompt rules remain first; citation IDs are allocated per request and validated only against supplied evidence. Error events intentionally avoid question, document text, paths, secrets, and provider payloads.

## Self-review / remaining limits

The existing Task 1–20 persistence layer exposes no answer/evidence tables, so this commit uses the `AskPersistence` seam and does not alter schema-owned files. The route currently buffers the service flow before emitting SSE chunks; the service itself remains streaming and the boundary event contract is stable. A later task may provide durable persistence wiring without changing the Ask core contract.

## Fixrunda 1/5

Reviewfynden om produktmässig no-op-persistence och buffrad SSE åtgärdades. `LlmStore.persistAsk` använder nu de befintliga Task 19-tabellerna `conversations`, `messages`, `model_calls` och `citations`; fråga, svar, locator/snippet, valideringsstatus, profilmodell och faktisk usage sparas lokalt utan nycklar. Ask-routen använder `respondOutputStream` och flushar varje SSE-event medan flowet körs. CLI `--json` samlar ett enda stabilt resultatobjekt.

`RequestBudget.measure(profile, request)` serialiserar providerens faktiska OpenAI- eller Anthropic-envelope med modell, stream, max output, tools/tool choice och respektive wrapper. Invalid citations utlöser högst en budgeterad correction-request med tillåtna evidens-ID:n; därefter sparas valideringen utan att ogiltiga ID:n blir länkar.

RED/GREEN: provider-envelope-testet och fokustesterna kördes efter implementation; kompileringsfelet för den otillgängliga Ktor-writer-API:n korrigerades till `respondOutputStream`, varefter testerna blev gröna.

Fixrunda-verifiering: `JAVA_HOME="$(asdf where java)" ./gradlew test --tests 'infoscry.ask.*' --tests infoscry.llm.RequestBudgetTest` — PASS. Full `check` återstår efter denna fixrunda.

## Fixrunda 2/5

Införde provider-neutral `LlmCompletionClient` med riktiga icke-streamade OpenAI- och Anthropic-envelope-anrop, utan streaming fallback. Correction-usage summeras och går vidare till persistence. `ContextPacker` kastar nu `ContextBudgetExceeded` i stället för ett generiskt fel. Migration 005 lägger till correction-linkage och evidence-/returned-ID-fält som skiljer supplied-but-uncited från invalid markers; `SchemaMigrator` och tester är uppdaterade.

RED/GREEN: schema-testet blev rött med gamla version 4-förväntningar och blev grönt efter uppdatering till version 5/6-fixture. Provider completion-/capture-testet och Ask-/budget-/migrationstesterna passerade.

Verifiering: `JAVA_HOME="$(asdf where java)" ./gradlew test --tests 'infoscry.ask.*' --tests infoscry.llm.RequestBudgetTest --tests infoscry.storage.SchemaMigratorTest` — PASS; `JAVA_HOME="$(asdf where java)" ./gradlew check` — PASS; `git diff --check` — PASS.

## Fixrunda 3/5

Budgeten har nu explicit `stream`-parameter och provider-envelope för correction mäts med `stream=false`. Correction-requesten innehåller allowed IDs samt hela relevanta supplied evidence med locator/text. Persistence skapar separata initial/correction `model_calls`, länkar correction via `correction_of`, beräknar profilbaserad kostnad, sparar cache reads och uppdaterar `usage_totals` transaktionellt.

RED/GREEN: fokustesterna kördes efter ändringen och blev gröna: `JAVA_HOME="$(asdf where java)" ./gradlew test --tests 'infoscry.ask.*' --tests infoscry.llm.RequestBudgetTest --tests infoscry.storage.SchemaMigratorTest` — PASS. Fullständig `AskServiceTest` med riktig SearchService-captured-flow behöver fortfarande stärkas; nuvarande AskServiceTest verifierar completion-boundaryn och usage, medan serviceflödet saknar full fake-search injection.

## Changed files

`src/main/kotlin/infoscry/ask/ContextPacker.kt`, `CitationValidator.kt`, `AskService.kt`; `src/main/kotlin/infoscry/llm/RequestBudget.kt`; `src/main/kotlin/infoscry/server/AskRoutes.kt`; `src/main/kotlin/infoscry/cli/AskCommand.kt`; `src/main/kotlin/infoscry/cli/RootCommand.kt`; `src/main/kotlin/infoscry/server/Routes.kt`; mirrored Ask/request-budget tests.

## Fixrunda 4/5

`AskService` har nu en liten `AskSearch`-port, medan produktionskonstruktorn fortsatt adaptrar den verkliga `SearchService`. Den nya service-testen kör därmed ett captured sökresultat mot riktig promptkomposition samt en scripted streaming-/completion-provider. Den bevisar en initial stream, exakt en completion-baserad correction efter `[S999]`, correctionens tillåtna evidence, separata initial/correction-usage-snapshots och att initial respektive correction-budgetfel gör noll otillåtna provideranrop och ingen persistence.

Usage överförs inte längre som en sammanlagd initial-siffra. Initiala och korrigerande `TokenUsage` (inklusive cache reads) persisteras var för sig i `model_calls`; varje kostnad beräknas med input-, output- och cache-read-priset i profilen. `usage_totals` summerar exakt dessa två calls en gång vardera.

Audit-rader skrivs per faktiskt call. Initialt supplied evidence och initiala returnerade markers ligger på initialt call; correctionens supplied evidence samt dess valid/invalid markers ligger på correctionens `model_call_id`, som länkar med `correction_of`. En valid marker behåller source unit, locator och snippet för reopen; en invalid marker har bara `invalid:<id>` och blir inte en länk. `CorrectionSnapshot.answer` prioriteras som det persisterade assistantsvaret när en correction finns.

RED/GREEN: en kontrollerad mutation som tog bort cache-read-priset gav avsiktligt röd `LlmStoreTest` på kostnadsassertionen. Efter återställning blev `JAVA_HOME="$(asdf where java)" ./gradlew test --tests 'infoscry.ask.*' --tests infoscry.llm.RequestBudgetTest --tests infoscry.llm.LlmStoreTest --tests infoscry.storage.SchemaMigratorTest` grön. `git diff --check` var grön.

Full `JAVA_HOME="$(asdf where java)" ./gradlew --no-daemon check` kunde inte slutföras: tråddumpen visade att den hängde i orelaterade `infoscry.jobs.JobRunnerTest.a stage waits for exclusive maintenance instead of being refused` (`JobRunnerTest.kt:189`), varefter endast den egna testprocessen stoppades. Frontenddelen hann verifiera 5/5 Vitest-test gröna före hängningen.
