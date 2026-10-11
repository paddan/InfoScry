package infoscry.server

import infoscry.investigate.EffortLevel
import infoscry.investigate.EffortPreset
import infoscry.investigate.InvestigationLimits
import infoscry.investigate.NativeReasoning
import infoscry.EXTERNAL_TAG
import infoscry.document.RestoreFixtures
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.extract.ContentUnitDraft
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.embedding.TestDocumentEmbedder
import infoscry.llm.FakeOpenAiResponse
import infoscry.llm.FakeOpenAiServer
import infoscry.llm.LlmCapabilityProbe
import infoscry.llm.LlmProfile
import infoscry.llm.LlmPromptRole
import infoscry.llm.LlmProvider
import infoscry.storage.Instants
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Tag

/**
 * The ticket-07 acceptance boundary: the real built reader in Chromium against a local InfoScry
 * server backed by a scripted local fake provider, temporary SQLite state, and a redistributable
 * text fixture. The reader is driven by `web/e2e/investigate-browser-acceptance.mjs`; this test owns
 * the server, the fake provider, the seeded unit, and the database assertions.
 *
 * Tagged `external` because it needs Node and the Playwright browser this machine has installed, so
 * the default suite stays runnable everywhere. Run it with `./gradlew externalTest`.
 */
@Tag(EXTERNAL_TAG)
class InvestigateBrowserAcceptanceTest {

    private lateinit var dataDir: Path
    private lateinit var harness: ApiTestServer
    private var citationDocumentId: DocumentId? = null
    private var citationCollectionId: String? = null
    private var citationUnitId: String? = null
    private var citationRevisionId: String? = null
    private var restoreRevisionId: String? = null

    @BeforeTest
    fun startServer() {
        dataDir = Files.createTempDirectory("infoscry-browser-acceptance")
        harness = ApiTestServer(dataDir, restoreEmbedder = { TestDocumentEmbedder() })
        // A new archive has no automatic Default, and every scenario here names one.
        harness.context.collections.create("Default")
    }

    @AfterTest
    fun stopServer() {
        harness.close()
        dataDir.toFile().deleteRecursively()
    }

    @Test
    fun `a follow-up reuses retained evidence across reload and reopens with stable citations`() {
        val unitId = addEvidenceUnit("Mira signed the note.", withHistory = true)
        FakeOpenAiServer(
            listOf(
                researchStream(unitId),
                answerStream("Mira signed the note [S1]."),
                FakeOpenAiResponse(body = titleCompletion("The signed note")),
                // The live reader asks the configured profile for its model catalog before follow-up input.
                FakeOpenAiResponse(body = """{"data":[{"id":"model"}]}"""),
                answerStream("The note was signed [S1]."),
                answerStream("A third answer [S1]."),
            ),
        ).use { fake ->
            createInvestigatorProfile(fake.url)
            setDefaultInvestigatorProfile()
            runScenario(
                "primary",
                extraEnvironment = mapOf(
                    "DOCUMENT_ID" to checkNotNull(citationDocumentId).value,
                    "COLLECTION_ID" to checkNotNull(citationCollectionId),
                    "CITATION_UNIT_ID" to checkNotNull(citationUnitId),
                    "CITATION_REVISION_ID" to checkNotNull(citationRevisionId),
                    "RESTORE_REVISION_ID" to checkNotNull(restoreRevisionId),
                ),
            )
            val modelLists = fake.requests.filter { it.method == "GET" && it.path.endsWith("/models") }
            assertEquals(1, modelLists.size, "the reader loads one configured model catalog")
            assertEquals("", modelLists.single().body)
            assertEquals(6, fake.handledRequests, "research, first answer, title, catalog GET, and two retained-evidence follow-ups")
            assertEquals(
                "The previous text belongs to the earlier reading.",
                harness.context.revisions.pages(harness.context.revisions.activeRevisionId(checkNotNull(citationDocumentId))!!).single().extractedText,
                "the browser restored the earlier text while the cited newer revision remained historical",
            )
            val storedCitationRevision = harness.context.database.read { connection ->
                connection.prepareStatement("SELECT revision_id FROM evidence_ledger WHERE source_unit_id = ? LIMIT 1").use { query ->
                    query.setString(1, checkNotNull(citationUnitId))
                    query.executeQuery().use { rows -> check(rows.next()); rows.getString(1) }
                }
            }
            assertEquals(citationRevisionId, storedCitationRevision, "the saved Investigate evidence stays pinned to its cited revision")
        }
    }

    @Test
    fun `an invalid citation is corrected once and shown as one adopted answer`() {
        val unitId = addEvidenceUnit("Mira signed the note.")
        FakeOpenAiServer(
            listOf(
                researchStream(unitId),
                answerStream("draft cites [S9]"),
                FakeOpenAiResponse(body = """{"choices":[{"message":{"role":"assistant","content":"corrected cites [S1]"}}],"usage":{"prompt_tokens":5,"completion_tokens":4}}"""),
                FakeOpenAiResponse(body = titleCompletion("Corrected note")),
            ),
        ).use { fake ->
            createInvestigatorProfile(fake.url)
            setDefaultInvestigatorProfile()
            runScenario("correction")
            assertEquals(4, fake.handledRequests, "one research round, one draft, one correction, one title")
            val conversationId = latestConversationId()
            val rows = assistantRows(conversationId)
            assertEquals(2, rows.size, "the draft and the correction are both retained as audit rows")
            assertEquals(1, rows.count { it.second == 1 }, "exactly the draft is superseded")
            assertEquals(1, rows.count { it.second == 0 }, "exactly the corrected answer is adopted")
        }
    }

    @Test
    fun `an HTTP rejection is visible and the next question recovers`() {
        val unitId = addEvidenceUnit("Mira signed the note.")
        FakeOpenAiServer(
            listOf(
                FakeOpenAiResponse(statusCode = 400, body = """{"error":{"message":"bad request"}}"""),
                FakeOpenAiResponse(body = titleCompletion("Rejected note")),
                researchStream(unitId),
                answerStream("Recovered answer [S1]."),
            ),
        ).use { fake ->
            createInvestigatorProfile(fake.url)
            setDefaultInvestigatorProfile()
            runScenario("rejection")
            assertEquals(4, fake.handledRequests, "the rejected turn is not retried, and recovery takes one research round plus one answer")
        }
    }

    @Test
    fun `a completion with omitted evidence finishes normally`() {
        FakeOpenAiServer(
            listOf(
                answerStream("No sources were needed."),
                FakeOpenAiResponse(body = titleCompletion("Empty note")),
            ),
        ).use { fake ->
            createInvestigatorProfile(fake.url)
            setDefaultInvestigatorProfile()
            runScenario("emptyEvidence")
            assertEquals(2, fake.handledRequests, "one answer and one title")
        }
    }

    @Test
    fun `an equivalent repeated call stops research with a notice and a cited answer`() {
        val unitId = addEvidenceUnit("Mira signed the note.")
        FakeOpenAiServer(
            listOf(
                researchStream(unitId),
                researchStream(unitId, whitespacePadded = true),
                answerStream("Found the signature [S1]."),
                FakeOpenAiResponse(body = titleCompletion("Repeat note")),
                answerStream("A recovered follow-up [S1]."),
            ),
        ).use { fake ->
            createInvestigatorProfile(fake.url)
            setDefaultInvestigatorProfile()
            runScenario("repeatLimit")
            // The equivalent repeat is refused without executing, and the turn answers from the
            // evidence it already collected, so the same call cannot research twice.
            assertEquals(5, fake.handledRequests, "two research rounds, one synthesis, one title, one follow-up")
        }
    }

    @Test
    fun `the configured round limit stops research with a notice and a cited answer`() {
        val unitId = addEvidenceUnit("Mira signed the note.")
        FakeOpenAiServer(
            listOf(
                researchStream(unitId),
                answerStream("Found the signature [S1]."),
                FakeOpenAiResponse(body = titleCompletion("Round note")),
            ),
        ).use { fake ->
            createInvestigatorProfile(fake.url)
            setDefaultInvestigatorProfile()
            val profile = harness.context.llm.findByName("investigator")!!
            harness.context.llm.update(profile.id, profile.copy(effortOverrides = mapOf(EffortLevel.LOW to EffortPreset(InvestigationLimits(1, 15, 120)))))
            runScenario("roundLimit")
            assertEquals(3, fake.handledRequests, "one research round, one synthesis, one title")
        }
    }

    @Test
    fun `effort hints use profile overrides with and without native reasoning`() {
        FakeOpenAiServer(listOf(
            answerStream("First effort answer."), FakeOpenAiResponse(body = titleCompletion("First effort")),
            answerStream("Second effort answer."), FakeOpenAiResponse(body = titleCompletion("Second effort")),
        )).use { fake ->
            createInvestigatorProfile(fake.url)
            val base = harness.context.llm.findByName("investigator")!!
            val native = base.copy(id = UUID.randomUUID().toString(), name = "native investigator", effortOverrides = mapOf(
                EffortLevel.HIGH to EffortPreset(InvestigationLimits(7, 9, 90), NativeReasoning.OpenAiReasoningEffort("high")),
            ))
            harness.context.llm.create(native)
            harness.context.llm.recordCapability(native.name, LlmCapabilityProbe(true, "2026-10-11T10:00:00Z"))
            setDefaultInvestigatorProfile()
            runScenario("effortHints")
            val requests = fake.requestBodies.filter { it.contains("\"messages\"") }
            assertTrue(requests.first().contains("\"reasoning_effort\":\"high\""))
            assertTrue(requests.drop(1).none { it.contains("reasoning_effort") }, "title and plain-profile turns stay at None")
        }
    }

    @Test
    fun `cancellation releases the controls and a fresh question succeeds`() {
        val unitId = addEvidenceUnit("Mira signed the note.")
        val hanging = FakeOpenAiResponse(
            stream = true,
            body = sse(listOf("""{"choices":[{"delta":{"content":"partial"},"finish_reason":null}]}""")),
            declaredLength = 100_000,
            // Long enough for the reader to cancel, short enough that the single fake-server handler
            // is free again for the recovery turn without a long sleep.
            holdMillis = 2_000,
        )
        FakeOpenAiServer(
            listOf(
                hanging,
                researchStream(unitId),
                answerStream("Recovered after cancel [S1]."),
            ),
        ).use { fake ->
            createInvestigatorProfile(fake.url)
            setDefaultInvestigatorProfile()
            try {
                runScenario("cancel")
            } catch (failure: Throwable) {
                throw IllegalStateException(
                    "${failure.message}\nhandled=${fake.handledRequests}\nbodies=${fake.requestBodies.joinToString("\n") { it.take(160) }}",
                    failure,
                )
            }
            assertTrue(fake.handledRequests >= 3, "cancellation then recovery makes the hanging call and the recovery turn")
        }
    }

    @Test
    fun `an unmeasured profile is refused with the remedy, measured from Admin, then a question runs`() {
        val unitId = addEvidenceUnit("Mira signed the note.")
        FakeOpenAiServer(
            listOf(
                // The LLM Admin draft explicitly fetches the model catalog from this fake before probing.
                FakeOpenAiResponse(body = """{"data":[{"id":"model"}]}"""),
                // The Admin check sends the text request first, then the forced ping tool call.
                answerStream("ok"),
                pingStream(),
                researchStream(unitId),
                answerStream("Mira signed the note [S1]."),
                FakeOpenAiResponse(body = titleCompletion("Measured note")),
            ),
        ).use { fake ->
            createInvestigatorProfile(fake.url, measured = false)
            setDefaultInvestigatorProfile()
            runScenario("unmeasuredProfile", fake.url)
            // The refused question sends nothing to the provider, so the script above is consumed exactly by
            // the two probe requests and the question's research, answer and title.
            val completions = fake.requests.filter { it.method == "POST" && it.path.endsWith("/chat/completions") }
            val modelLists = fake.requests.filter { it.method == "GET" && it.path.endsWith("/models") }
            assertEquals(5, completions.size, "two probes, research, one answer and one title")
            assertEquals(
                1,
                modelLists.size,
                "draft catalog request data: ${fake.requests.joinToString { "${it.method} ${it.path} body=${it.body}" }}",
            )
            assertEquals("", modelLists.single().body, "the model-catalog GET carries no completion body")
            assertEquals(6, fake.handledRequests, "five completion POSTs plus the separate Admin model-list GET")
            assertTrue(
                harness.context.llm.findByName("investigator")!!.toolCallingSupported,
                "the Admin check must persist the measured tool-calling state",
            )
        }
    }

    // ---- Harness ----

    private fun runScenario(name: String, catalogEndpoint: String? = null, extraEnvironment: Map<String, String> = emptyMap()) {
        val script = Paths.get(System.getProperty("user.dir"), "web", "e2e", "investigate-browser-acceptance.mjs")
        check(Files.exists(script)) { "browser acceptance script is missing: $script" }
        // The output goes to a file so the timeout below can expire before the output is read: a
        // blocking read of the child's stream would wait for EOF and defeat the timeout entirely.
        val outputFile = Files.createTempFile("infoscry-browser-", ".log")
        val process = ProcessBuilder("node", script.toString())
            .directory(script.parent.toFile())
            .redirectErrorStream(true)
            .redirectOutput(outputFile.toFile())
            .apply {
                environment()["BASE_URL"] = harness.url
                environment()["SCENARIO"] = name
                if (catalogEndpoint != null) environment()["CATALOG_ENDPOINT"] = catalogEndpoint
                extraEnvironment.forEach { (key, value) -> environment()[key] = value }
            }
            .start()
        val finished = process.waitFor(180, TimeUnit.SECONDS)
        val output = Files.readString(outputFile)
        outputFile.toFile().delete()
        if (!finished) {
            process.destroyForcibly()
            error("browser scenario '$name' timed out\n$output")
        }
        check(process.exitValue() == 0) { "browser scenario '$name' failed:\n$output" }
    }

    private fun createInvestigatorProfile(endpoint: String, measured: Boolean = true) {
        harness.context.llm.create(
            LlmProfile(
                id = UUID.randomUUID().toString(),
                name = "investigator",
                provider = LlmProvider.OPENAI_COMPATIBLE,
                model = "model",
                contextWindow = 10_000,
                maxOutputTokens = 64,
                inputPricePerMillion = 0.0,
                outputPricePerMillion = 0.0,
                cacheReadPricePerMillion = 0.0,
                enabled = true,
                endpoint = endpoint,
            ),
        )
        if (measured) {
            harness.context.llm.recordCapability(
                "investigator",
                LlmCapabilityProbe(toolCallingSupported = true, checkedAt = "2026-09-21T10:00:00Z"),
            )
        }
    }

    /** A forced tool call to `ping`, the shape the capability probe's second request accepts. */
    private fun pingStream(): FakeOpenAiResponse = FakeOpenAiResponse(
        stream = true,
        body = sse(
            listOf(
                """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"ping-1","function":{"name":"ping","arguments":"{}"}}]},"finish_reason":"tool_calls"}]}""",
            ),
        ),
    )

    private fun setDefaultInvestigatorProfile() {
        check(harness.context.llm.setDefault(LlmPromptRole.INVESTIGATE, "investigator")) {
            "the browser acceptance profile must become the Investigate default"
        }
    }

    private fun latestConversationId(): String = harness.context.database.read { connection ->
        connection.prepareStatement("SELECT id FROM conversations WHERE mode = 'INVESTIGATE' ORDER BY created_at DESC LIMIT 1").use { statement ->
            statement.executeQuery().use { rows -> check(rows.next()) { "no Investigate conversation was stored" }; rows.getString(1) }
        }
    }

    private fun assistantRows(conversationId: String): List<Pair<String, Int>> = harness.context.database.read { connection ->
        connection.prepareStatement(
            "SELECT content, superseded FROM messages WHERE conversation_id = ? AND role = 'assistant' AND content <> '' ORDER BY seq",
        ).use { statement ->
            statement.setString(1, conversationId)
            statement.executeQuery().use { rows ->
                buildList { while (rows.next()) add(rows.getString(1) to rows.getInt(2)) }
            }
        }
    }

    private fun answerStream(text: String): FakeOpenAiResponse = FakeOpenAiResponse(
        stream = true,
        body = sse(
            listOf("""{"choices":[{"delta":{"content":"$text"},"finish_reason":"stop"}],"usage":{"prompt_tokens":20,"completion_tokens":8}}"""),
        ),
    )

    /** One research response that reads [unitId]; [whitespacePadded] produces an equivalent call. */
    private fun researchStream(unitId: String, whitespacePadded: Boolean = false): FakeOpenAiResponse {
        val raw = if (whitespacePadded) {
            """{ "contentUnitId" : "$unitId" }"""
        } else {
            """{"contentUnitId":"$unitId"}"""
        }
        val arguments = raw.replace("\"", "\\\"")
        return FakeOpenAiResponse(
            stream = true,
            body = sse(
                listOf(
                    """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call-1","type":"function","function":{"name":"read_content_unit","arguments":"$arguments"}}]},"finish_reason":null}]}""",
                    """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
                    """{"choices":[],"usage":{"prompt_tokens":1,"completion_tokens":1}}""",
                ),
            ),
        )
    }

    private fun sse(dataLines: List<String>): String =
        dataLines.map { "data: $it\n\n" }.joinToString("") + "data: [DONE]\n\n"

    private fun titleCompletion(title: String): String =
        """{"choices":[{"message":{"role":"assistant","content":"$title"}}],"usage":{"prompt_tokens":9,"completion_tokens":3}}"""

    private fun addEvidenceUnit(text: String, withHistory: Boolean = false): String {
        val collection = harness.context.collectionService.requireActiveByNameOrId("Default")
        if (withHistory) {
            val source = Files.createDirectories(dataDir.resolve("sources")).resolve("browser-acceptance.png")
            val image = BufferedImage(640, 360, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().also { graphics ->
                graphics.color = Color.WHITE
                graphics.fillRect(0, 0, image.width, image.height)
                graphics.color = Color.BLACK
                graphics.font = java.awt.Font("SansSerif", java.awt.Font.PLAIN, 24)
                graphics.drawString("Mira signed the note.", 40, 80)
                graphics.dispose()
            }
            ImageIO.write(image, "png", source.toFile())
            val document = harness.context.library.importFile(collection.id, source).document
            val unitId = ContentUnitId.new()
            val locator = SourceLocation.Image(source.fileName.toString())
            val old = RestoreFixtures.stage(
                harness.context,
                document.id,
                parentRevisionId = null,
                pages = listOf("The previous text belongs to the earlier reading."),
                unitIds = listOf(unitId),
                locators = listOf(locator),
                extractionMethods = listOf(ExtractionMethod.DIRECT_TEXT),
            )
            runBlocking { harness.context.revisionPublication.publish(document.id, null, old) }
            val current = RestoreFixtures.stage(
                harness.context,
                document.id,
                parentRevisionId = old,
                pages = listOf(text),
                unitIds = listOf(unitId),
                locators = listOf(locator),
                extractionMethods = listOf(ExtractionMethod.DIRECT_TEXT),
            )
            runBlocking { harness.context.revisionPublication.publish(document.id, old, current) }
            citationDocumentId = document.id
            citationCollectionId = collection.id.value
            citationUnitId = unitId.value
            citationRevisionId = current
            restoreRevisionId = old
            assertEquals(current, harness.context.revisions.activeRevisionId(document.id))
            return unitId.value
        }
        val documentId = DocumentId.new()
        val now = Instants.now()
        harness.context.documents.insert(
            Document(
                id = documentId,
                collectionId = collection.id,
                sha256 = "f".repeat(64),
                mediaType = "text/plain",
                originalFilename = "browser-acceptance.txt",
                sourcePath = "tests/browser-acceptance.txt",
                sizeBytes = text.length.toLong(),
                status = DocumentStatus.COMPLETE,
                createdAt = now,
                updatedAt = now,
                title = "browser acceptance source",
                author = null,
                language = "en",
            ),
        )
        val unit = harness.context.content.commitExtractedUnit(
            documentId = documentId,
            fingerprint = ExtractionFingerprint.of(
                "f".repeat(64),
                ExtractionSettings(ocrLanguages = "eng", extractorSchemaVersion = "browser-acceptance"),
            ),
            key = "browser-acceptance-unit",
            ordinal = 0,
            draft = ContentUnitDraft(
                locator = SourceLocation.TextLines(1, 1),
                extractedText = text,
                searchText = text,
                method = ExtractionMethod.DIRECT_TEXT,
            ),
            artifactRoot = harness.context.paths.libraryDir,
        ).unit
        return unit.id.value
    }
}
