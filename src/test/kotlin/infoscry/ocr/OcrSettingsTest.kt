package infoscry.ocr

import infoscry.domain.CollectionId
import infoscry.domain.JobId
import infoscry.extract.ExtractionFingerprint
import infoscry.extract.ExtractionSettings
import infoscry.jobs.ImportJobPayload
import infoscry.llm.LlmProvider
import infoscry.storage.CollectionStore
import infoscry.storage.Database
import infoscry.storage.DuplicateOcrProfileNameException
import infoscry.storage.JobStore
import infoscry.storage.OcrProfileStore
import infoscry.storage.SchemaMigrator
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * The OCR attempt settings: what an attempt records, what changes a fingerprint, and what a profile
 * edit must not change.
 *
 * The reuse rules are the point of this file. A transcription fingerprint decides whether committed
 * page text may be reused and a review fingerprint decides whether a comparison may be reused; the two
 * must not invalidate each other, or a reviewer change would redo OCR and an OCR change would silently
 * accept an old comparison. The pinned digests are the other half: settings that name no OCR attempt
 * have to hash exactly as they did before OCR attempts existed, so checkpoints and queued jobs written
 * by the previous build keep matching.
 */
class OcrSettingsTest {

    private lateinit var dataDir: Path
    private lateinit var database: Database
    private lateinit var store: OcrProfileStore
    private lateinit var profiles: OcrProfileService

    /** Makes every profile this test creates distinct, so a fixture is never a duplicate by accident. */
    private var profileCount = 0

    @BeforeTest
    fun openDataDirectory() {
        dataDir = Files.createTempDirectory("infoscry-ocr-settings")
        database = Database(dataDir.resolve("state.db"))
        SchemaMigrator(database).migrate()
        store = OcrProfileStore(database)
        profiles = OcrProfileService(store)
    }

    @AfterTest
    fun closeDataDirectory() {
        database.close()
        dataDir.toFile().deleteRecursively()
    }

    // ---- The pinned legacy fingerprint ----

    @Test
    fun `settings without an OCR attempt hash exactly as the previous build did`() {
        // These two digests were read off this build before the canonical string gained its optional OCR
        // block, and they are the whole promise of backward-compatible reuse: a checkpoint committed by
        // the previous build must keep matching, or a paused import would repeat work it already did.
        assertEquals(
            "ed56e322e8ada616fcd366931606965265098b877c996cc832865192542a84c3",
            ExtractionFingerprint.of("a".repeat(64), ExtractionSettings(ocrLanguages = "eng")).value,
        )
        assertEquals(
            "1649c37017a927b0960b9e87434b7e0b227314ea03e13af805a7106adf4b4750",
            ExtractionFingerprint.of(
                "b".repeat(64),
                ExtractionSettings(
                    ocrLanguages = "eng+swe",
                    ocrTool = "tesseract 5.3.0",
                    renderDpi = 300,
                    ebookTool = "calibre 7.2",
                ),
            ).value,
        )
    }

    @Test
    fun `an OCR attempt identity never hashes like no attempt at all`() {
        val plain = ExtractionSettings(ocrLanguages = "eng")
        val attempting = plain.copy(ocrAttempt = attemptIdentity())

        assertNotEquals(plain, attempting, "'no attempt' and 'this attempt' are different settings")
        assertNotEquals(
            ExtractionFingerprint.of("c".repeat(64), plain),
            ExtractionFingerprint.of("c".repeat(64), attempting),
            "an attempt that named its engine must not reuse the legacy attempt's checkpoints",
        )
    }

    @Test
    fun `a line break in an attempt field cannot compose the fields that follow it`() {
        // One field per line is only unambiguous while no value carries a line of its own. A tool version
        // ending in "\nocr_model=y" with model "z" composed exactly the lines of tool "x" with model
        // "y\nocr_model=z": two different attempts, one fingerprint. The value is refused instead.
        val settings = ExtractionSettings(ocrLanguages = "eng")
        val splicedTool = settings.copy(
            ocrAttempt = attemptIdentity().copy(toolVersion = "x\nocr_model=y", modelVersion = "z"),
        )
        val splicedModel = settings.copy(
            ocrAttempt = attemptIdentity().copy(toolVersion = "x", modelVersion = "y\nocr_model=z"),
        )
        assertNotEquals(
            splicedTool.ocrAttempt,
            splicedModel.ocrAttempt,
            "the two attempts are different readings, so they may not share a fingerprint",
        )

        val toolFailure = assertFailsWith<IllegalArgumentException> {
            ExtractionFingerprint.of("e".repeat(64), splicedTool)
        }
        assertContains(toolFailure.message.orEmpty(), "ocr_attempt_tool")

        val modelFailure = assertFailsWith<IllegalArgumentException> {
            ExtractionFingerprint.of("e".repeat(64), splicedModel)
        }
        assertContains(modelFailure.message.orEmpty(), "ocr_model")
    }

    @Test
    fun `a queued payload keeps its attempt identity and a legacy payload keeps decoding`() {
        val legacy = """{"collectionId":"c1","sources":["/tmp/a.pdf"],"settings":{"ocrLanguages":"eng"}}"""
        val decodedLegacy = ImportJobPayload.decode(legacy)
        assertNull(decodedLegacy.settings.ocrAttempt, "a payload written before OCR attempts names none")
        assertEquals(
            "ed56e322e8ada616fcd366931606965265098b877c996cc832865192542a84c3",
            ExtractionFingerprint.of("a".repeat(64), decodedLegacy.settings).value,
        )

        val queued = ImportJobPayload.of(
            collectionId = CollectionId("c1"),
            sources = listOf("/tmp/b.pdf"),
            settings = ExtractionSettings(ocrLanguages = "eng", ocrAttempt = attemptIdentity()),
        )
        assertEquals(attemptIdentity(), ImportJobPayload.decode(queued.encode()).settings.ocrAttempt)
    }

    // ---- The two fingerprints ----

    @Test
    fun `a discovered runtime identity invalidates reuse where an undiscovered one does not`() {
        // The two fingerprints a checkpoint can be keyed by, on either side of the runtime that produced it:
        // "this machine's runtime said it was X", "it said it was Y", and "nobody could describe it". The
        // last is not a wildcard — a page committed under weights nobody named may not be reused under named
        // ones, because nothing says the pixels were read the same way.
        val undiscovered = attemptIdentity()
        val runtimeA = undiscovered.copy(runtimeIdentity = "surya-ocr 0.22.1 backend llamacpp surya-2.gguf:1")
        val runtimeB = undiscovered.copy(runtimeIdentity = "surya-ocr 0.22.1 backend llamacpp surya-2.gguf:2")
        val settings = { attempt: OcrAttemptIdentity ->
            ExtractionSettings(ocrLanguages = "eng", ocrAttempt = attempt)
        }

        val none = ExtractionFingerprint.of("7".repeat(64), settings(undiscovered))
        val first = ExtractionFingerprint.of("7".repeat(64), settings(runtimeA))
        assertNotEquals(none, first, "a page committed without a known runtime was reused under a known one")
        assertNotEquals(
            first,
            ExtractionFingerprint.of("7".repeat(64), settings(runtimeB)),
            "another runtime read the page again, so its commit is not this attempt's to reuse",
        )
        assertEquals(
            first,
            ExtractionFingerprint.of("7".repeat(64), settings(runtimeA)),
            "the same runtime always hashes the same",
        )

        // And the per-page fingerprint is keyed by the same identity: a page read by another runtime is not
        // the reading this attempt is about to make either.
        assertNotEquals(
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, runtimeA),
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, runtimeB),
        )
        assertNotEquals(
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, undiscovered),
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, runtimeA),
        )
    }

    @Test
    fun `changing the reviewer changes the review fingerprint and leaves transcription alone`() {
        val snapshot = snapshotFor(engine = OcrEngine.LLM, withReviewer = true)
        val reviewProfileRevisionId = snapshot.reviewProfileRevisionId!!
        val reReviewed = snapshot.copy(reviewProfileRevisionId = "reviewer-revision-b")

        assertNotEquals(
            reviewFingerprint(reviewerRevision = reviewProfileRevisionId),
            reviewFingerprint(reviewerRevision = "reviewer-revision-b"),
            "a different reviewer revision is a different comparison",
        )
        assertNotEquals(
            reviewFingerprint(reviewerRevision = reviewProfileRevisionId, prompt = OCR_REVIEW_PROMPT_VERSION + 1),
            reviewFingerprint(reviewerRevision = reviewProfileRevisionId),
            "another review prompt is another comparison",
        )
        assertNotEquals(
            reviewFingerprint(reviewerRevision = reviewProfileRevisionId, policy = OCR_POLICY_VERSION + 1),
            reviewFingerprint(reviewerRevision = reviewProfileRevisionId),
            "another policy version is another comparison",
        )
        assertEquals(
            reviewFingerprint(reviewerRevision = reviewProfileRevisionId),
            reviewFingerprint(reviewerRevision = reviewProfileRevisionId),
            "the same inputs always hash the same",
        )

        assertEquals(
            snapshot.attemptIdentity(),
            reReviewed.attemptIdentity(),
            "no reviewer field reaches the attempt identity",
        )
        assertEquals(
            ExtractionFingerprint.of("d".repeat(64), ExtractionSettings("eng", ocrAttempt = snapshot.attemptIdentity())),
            ExtractionFingerprint.of(
                "d".repeat(64),
                ExtractionSettings("eng", ocrAttempt = reReviewed.attemptIdentity()),
            ),
            "a reviewer edit must not invalidate compatible transcription",
        )
    }

    @Test
    fun `changing the transcription prompt or the profile revision invalidates transcription`() {
        val snapshot = snapshotFor(engine = OcrEngine.LLM)
        val page = OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, snapshot.attemptIdentity())

        val rePrompted = snapshot.copy(transcriptionPromptVersion = snapshot.transcriptionPromptVersion + 1)
        assertNotEquals(
            page,
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, rePrompted.attemptIdentity()),
            "another prompt reads a page differently, so committed text may not be reused",
        )

        val reProfiled = snapshot.copy(transcriptionProfileRevisionId = "another-revision")
        assertNotEquals(
            page,
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, reProfiled.attemptIdentity()),
            "another model revision is another reading",
        )
        assertNotEquals(
            page,
            OcrTranscriptionFingerprint.of("doc-1", "unit-2", 1, snapshot.attemptIdentity()),
            "page identity and ordinal are part of the fingerprint",
        )
        assertEquals(
            page,
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, snapshot.attemptIdentity()),
            "the same inputs always hash the same",
        )
    }

    @Test
    fun `a picture read under another decoding policy is not the reading this attempt may reuse`() {
        // A picture is read from the managed copy or from a bounded copy of it depending on the rule that
        // decides what fits the bound a tool is handed, and the two are different pixels. The document-level
        // extraction fingerprint is the only key an attempt's checkpoints answer to, so the rule has to be in
        // the attempt identity — and in the page-level transcription fingerprint, for the same reason one
        // page of it is not the other.
        val current = attemptIdentity()
        val stricter = current.copy(
            pictureDecodingPolicyVersion = current.pictureDecodingPolicyVersion + 1,
        )

        assertNotEquals(
            ExtractionFingerprint.of("f".repeat(64), ExtractionSettings("eng", ocrAttempt = current)),
            ExtractionFingerprint.of("f".repeat(64), ExtractionSettings("eng", ocrAttempt = stricter)),
            "a picture decoded under another policy reused this attempt's committed pages",
        )
        assertNotEquals(
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, current),
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, stricter),
            "a page read under another policy was treated as a reading of the same image",
        )
        assertEquals(
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, current),
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, attemptIdentity()),
            "the same attempt always hashes the same",
        )
        assertEquals(PageImage.PICTURE_DECODING_POLICY_VERSION, current.pictureDecodingPolicyVersion)
        assertEquals(
            PageImage.PICTURE_DECODING_POLICY_VERSION,
            snapshotFor(OcrEngine.TESSERACT).attemptIdentity().pictureDecodingPolicyVersion,
            "the snapshot that admission builds names the policy the running build reads pictures with",
        )
        assertFailsWith<IllegalArgumentException> { current.copy(pictureDecodingPolicyVersion = 0) }
    }

    @Test
    fun `a line break in a fingerprint field cannot compose the fields that follow it`() {
        // Two different identities that would compose exactly the same lines — and therefore the same digest,
        // and therefore the same reuse decision — if the values were joined without the guard: tool
        // "x\nmodel=y" with model "z" against tool "x" with model "y\nmodel=z".
        val first = attemptIdentity().copy(toolVersion = "x\nmodel=y", modelVersion = "z")
        val second = attemptIdentity().copy(toolVersion = "x", modelVersion = "y\nmodel=z")

        assertFailsWith<IllegalArgumentException> {
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, first)
        }
        assertFailsWith<IllegalArgumentException> {
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, second)
        }
        // The review fingerprint composes its fields the same way, so the same guard has to cover it too.
        assertFailsWith<IllegalArgumentException> {
            reviewFingerprint("reviewer-1", candidate = "shot\npolicy=2")
        }
    }

    @Test
    fun `a runtime that reports the word for absence is still a discovered identity`() {
        // A probe that truthfully reports the literal string "none" (or "absent") describes a runtime, and a
        // page committed while nothing was known must not be reused for it: the two states are tagged rather
        // than spelled, so no description can compose the line absence uses.
        val absent = attemptIdentity()
        val wordNone = absent.copy(runtimeIdentity = "none")
        val wordAbsent = absent.copy(runtimeIdentity = "absent")
        val taggedWord = absent.copy(runtimeIdentity = "present:x")
        val real = absent.copy(runtimeIdentity = "surya-ocr 0.22.1 backend llamacpp")
        val identities = listOf(
            absent,
            wordNone,
            wordAbsent,
            taggedWord,
            real,
            // The other descriptions an attempt carries are free-form too — a tool or a store supplies them —
            // so the same property has to hold for each: a version that reads "none" is a version.
            absent.copy(toolVersion = "none"),
            absent.copy(modelVersion = "absent"),
            // A profile revision can only be named by the image engine that reads through one — the record
            // refuses it for a local engine — so the absent form is unreachable there today and this tag is
            // defence in depth rather than a live edge.
            absent.copy(engine = OcrEngine.LLM, profileRevisionId = "none"),
        )

        val transcription = identities.map { identity ->
            OcrTranscriptionFingerprint.of("doc-1", "unit-1", 0, identity)
        }
        assertEquals(
            transcription.size,
            transcription.distinct().size,
            "no runtime description may hash like another, or like its own absence",
        )

        val extraction = identities.map { identity ->
            ExtractionFingerprint.of(
                "c".repeat(64),
                ExtractionSettings(ocrLanguages = "eng", ocrAttempt = identity),
            )
        }
        assertEquals(
            extraction.size,
            extraction.distinct().size,
            "the extraction fingerprint has to tell the same four states apart",
        )
    }

    @Test
    fun `a legacy tool version that reads as absence is escaped rather than composed away`() {
        // The legacy fields cannot change their absent form — committed checkpoints answer to it — so the
        // pathological present value is marked instead: a probe accepts a tool's own output line, and a tool
        // that truthfully prints the absent word is a tool that was found.
        val of = { tool: String?, ebook: String? ->
            ExtractionFingerprint.of(
                "d".repeat(64),
                ExtractionSettings(ocrLanguages = "eng", ocrTool = tool, ebookTool = ebook),
            )
        }

        assertEquals(
            4,
            setOf(of(null, null), of("none", null), of("escaped:none", null), of("tesseract 5.3.0", null))
                .size,
            "a tool version that reads as the absent word is a version, not an absence",
        )
    }

    @Test
    fun `a candidate or baseline change invalidates only the review`() {
        val baseline = reviewFingerprint(reviewerRevision = "reviewer-revision-a")

        assertNotEquals(baseline, reviewFingerprint(reviewerRevision = "reviewer-revision-a", candidate = "shot-2"))
        assertNotEquals(
            baseline,
            reviewFingerprint(reviewerRevision = "reviewer-revision-a", baselineRevisionId = null),
            "a page with no baseline is not the same comparison",
        )
    }

    // ---- The snapshot ----

    @Test
    fun `a snapshot round trips with every field the attempt records`() {
        val snapshot = snapshotFor(engine = OcrEngine.LLM, withReviewer = true)
        val json = Json { encodeDefaults = true }

        assertEquals(
            snapshot,
            json.decodeFromString(OcrSettingsSnapshot.serializer(), json.encodeToString(OcrSettingsSnapshot.serializer(), snapshot)),
        )
        assertEquals(OcrEngine.LLM, snapshot.engine)
        assertEquals("eng", snapshot.language)
        assertEquals(0, snapshot.externalPageLimit)
        assertNull(snapshot.autoValidationId, "auto replacement is disabled until a policy is measured")
    }

    @Test
    fun `a snapshot refuses an engine that disagrees with its profiles`() {
        val local = snapshotFor(engine = OcrEngine.TESSERACT)
        assertFailsWith<IllegalArgumentException> { local.copy(engine = OcrEngine.LLM) }
        assertFailsWith<IllegalArgumentException> { local.copy(transcriptionProfileRevisionId = "revision-a") }
        assertFailsWith<IllegalArgumentException> {
            snapshotFor(engine = OcrEngine.LLM).copy(transcriptionProfileRevisionId = null)
        }
        assertFailsWith<IllegalArgumentException> { local.copy(language = "  ") }
        assertFailsWith<IllegalArgumentException> { local.copy(externalPageLimit = -1) }
        assertFailsWith<IllegalArgumentException> { local.copy(transcriptionPromptVersion = 0) }
        assertFailsWith<IllegalArgumentException> { local.copy(policyVersion = 0) }
    }

    @Test
    fun `an attempt snapshotted at admission keeps revision A while a later attempt resolves B`() {
        val transcriber = profiles.create("Transcriber", draft(model = "vision-1"), enabled = true)
        val settings = CollectionOcrSettings(
            language = "eng",
            engine = OcrEngine.LLM,
            transcriptionProfileId = transcriber.id,
        )
        val admitted = profiles.snapshotFor(settings, extractorVersion = "1")

        val edited = profiles.update(transcriber.id, "Transcriber", draft(model = "vision-2"), enabled = true)
        assertNotEquals(transcriber.revision.revisionId, edited.revision.revisionId, "an edit is a new revision")

        assertEquals(
            transcriber.revision.revisionId,
            admitted.transcriptionProfileRevisionId,
            "a snapshot references the revision it was admitted with, never the profile it came from",
        )
        assertEquals("vision-1", store.findRevision(admitted.transcriptionProfileRevisionId!!)!!.model)
        assertEquals(
            edited.revision.revisionId,
            profiles.snapshotFor(settings, extractorVersion = "1").transcriptionProfileRevisionId,
            "the next attempt resolves the profile again and gets the new revision",
        )
    }

    @Test
    fun `admission records the runtime the engine reports and the queued job carries it`() {
        val probe = RuntimeProbe("surya-ocr 0.22.1 backend llamacpp surya-2.gguf:1")
        val probing = OcrProfileService(store, engineFor = { _, _, _ -> probe })
        val settings = CollectionOcrSettings(language = "eng", engine = OcrEngine.SURYA)

        val snapshot = runBlocking { probing.withProbedRuntime(probing.snapshotFor(settings, extractorVersion = "1")) }

        assertEquals(probe.identity, snapshot.runtimeIdentity)
        // The probed identity travels in the queued job, so a process that resumes it reads under the runtime
        // that was admitted — and the attempt does not ask the engine again, which is what would let an
        // upgrade in between silently redefine the key an earlier attempt's committed pages were written under.
        val queued = ImportJobPayload.decode(
            ImportJobPayload.of(
                collectionId = CollectionId("default"),
                sources = listOf("/tmp/scan.pdf"),
                settings = ExtractionSettings(ocrLanguages = "eng").forOcrSettings(snapshot),
                ocr = snapshot,
            ).encode(),
        )
        assertEquals(snapshot, queued.ocr)
        assertEquals(probe.identity, queued.settings.ocrAttempt?.runtimeIdentity)
        assertEquals(1, probe.probes)
    }

    @Test
    fun `a snapshot resolved before a profile is disabled still describes its own reading`() {
        val transcriber = profiles.create("Transcriber", draft(), enabled = true)
        val admitted = profiles.snapshotFor(
            CollectionOcrSettings(language = "eng", engine = OcrEngine.LLM, transcriptionProfileId = transcriber.id),
            extractorVersion = "1",
        )

        assertTrue(profiles.disable(transcriber.id))
        assertFailsWith<IllegalArgumentException> {
            profiles.requireSelectable(transcriber.id, OcrProfileRole.TRANSCRIPTION)
        }
        assertEquals(transcriber.revision.revisionId, admitted.transcriptionProfileRevisionId)
        assertEquals("vision-model", store.findRevision(admitted.transcriptionProfileRevisionId!!)!!.model)
    }

    // ---- Profiles and their revisions ----

    @Test
    fun `an edit adds an immutable revision and keeps the one an attempt already references`() {
        val created = profiles.create("Transcriber", draft(model = "vision-1"), enabled = true)
        assertEquals(1, created.revision.sequence)
        assertEquals(created.id, created.revision.profileId)

        val edited = profiles.update(created.id, "Transcriber renamed", draft(model = "vision-2"), enabled = true)

        assertEquals(created.id, edited.id, "an edit never changes a profile's identity")
        assertEquals("Transcriber renamed", edited.name)
        assertEquals(2, edited.revision.sequence)
        assertEquals("vision-2", edited.revision.model)
        assertEquals("vision-1", store.findRevision(created.revision.revisionId)!!.model, "the old revision survives")
        assertEquals(1, store.list().size, "an edit is not a second profile")
    }

    @Test
    fun `disabling a profile prevents selection and preserves its revisions`() {
        val created = profiles.create("Transcriber", draft(), enabled = true)

        assertTrue(profiles.disable(created.id))
        assertFalse(profiles.require(created.id).enabled)
        assertNotNull(store.findRevision(created.revision.revisionId), "a disabled profile keeps its revisions")
        assertFalse(profiles.disable("missing"), "disabling an unknown profile reports that it is unknown")
    }

    @Test
    fun `a revision that still holds a credential cannot break the profile listing`() {
        val now = "2026-09-30T09:00:00Z"
        val secret = "hunter2-not-a-real-credential"
        // Written around the revision type that refuses such a URL, the way a hand edit or a future bug would
        // write it: one such revision used to fail every profile this archive had.
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "INSERT INTO ocr_profile_revisions (revision_id, profile_id, sequence, provider, endpoint, " +
                        "model, api_key_environment_variable, context_window, max_output_tokens, " +
                        "input_price_per_million, output_price_per_million, created_at) VALUES " +
                        "('hand-edited', 'hand-edited-profile', 1, 'OPENAI_COMPATIBLE', " +
                        "'https://user:$secret@vision.example.com/v1', 'vision-model', 'MY_SECRET_KEY', " +
                        "32000, 4096, 0.0, 0.0, '$now')",
                )
                statement.execute(
                    "INSERT INTO ocr_profiles (id, name, enabled, current_revision_id, created_at, " +
                        "updated_at) VALUES ('hand-edited-profile', 'Hand-edited vision', 1, 'hand-edited', " +
                        "'$now', '$now')",
                )
            }
        }

        val listed = store.list()

        val profile = listed.single { it.name == "Hand-edited vision" }
        assertEquals("https://vision.example.com/v1", profile.revision.endpoint, "the credential is dropped")
        assertFalse(profile.enabled, "a repaired address is not selectable until a person reviews it")
        assertFalse(listed.any { it.revision.endpoint.contains(secret) }, "no profile read carries the credential")
        assertEquals(
            "https://vision.example.com/v1",
            store.findRevision("hand-edited")!!.endpoint,
            "every read of the revision repairs it, not only the joined profile",
        )
    }

    @Test
    fun `a profile validates its name, bounds, URL and environment variable name`() {
        assertFailsWith<IllegalArgumentException> { profiles.create("   ", draft(), enabled = true) }
        assertFailsWith<IllegalArgumentException> { draft(model = " ") }
        assertFailsWith<IllegalArgumentException> { draft(contextWindow = 0) }
        assertFailsWith<IllegalArgumentException> { draft(maxOutputTokens = 0) }
        assertFailsWith<IllegalArgumentException> { draft(inputPricePerMillion = -0.5) }
        assertFailsWith<IllegalArgumentException> { draft(outputPricePerMillion = Double.NaN) }
        assertFailsWith<IllegalArgumentException> { draft(outputPricePerMillion = Double.POSITIVE_INFINITY) }
        assertFailsWith<IllegalArgumentException> { draft(endpoint = "not a url") }
        assertFailsWith<IllegalArgumentException> { draft(endpoint = "/v1/chat") }
        // An absolute URI is not yet something this seam can dispatch to: the transport posts to a host over
        // http or https, so another scheme or a missing host has to fail here and not at the first paid call.
        assertFailsWith<IllegalArgumentException> { draft(endpoint = "ftp://api.example.com/v1") }
        assertFailsWith<IllegalArgumentException> { draft(endpoint = "file:///tmp/vision-model") }
        assertFailsWith<IllegalArgumentException> { draft(endpoint = "https:///v1") }
        assertFailsWith<IllegalArgumentException> { draft(apiKeyEnvironmentVariable = "sk-this-is-a-key") }
        assertFailsWith<IllegalArgumentException> { draft(apiKeyEnvironmentVariable = "") }
    }

    @Test
    fun `a snapshot reads both selected profiles at once and names the slot that is not selectable`() {
        val transcription = profiles.create("Transcriber", draft(), enabled = true)
        val reviewer = profiles.create("Reviewer", draft(), enabled = true)
        val settings = CollectionOcrSettings(
            language = "eng",
            engine = OcrEngine.LLM,
            transcriptionProfileId = transcription.id,
            reviewProfileId = reviewer.id,
        )

        assertEquals(
            setOf(transcription.id, reviewer.id),
            store.findByIds(listOf(transcription.id, reviewer.id, "missing")).keys,
            "one read answers exactly the profiles that exist",
        )
        assertEquals(
            transcription.revision.revisionId,
            profiles.snapshotFor(settings, extractorVersion = "1").transcriptionProfileRevisionId,
        )

        // Retired after admission: the next snapshot refuses, and says which of the two slots was wrong
        // rather than reporting that the first read it happened to make was missing.
        profiles.disable(reviewer.id)
        val refusal = assertFailsWith<IllegalArgumentException> {
            profiles.snapshotFor(settings, extractorVersion = "1")
        }
        assertContains(
            refusal.message ?: "",
            "review",
            message = "the refusal names the slot that is not selectable",
        )
    }

    @Test
    fun `a duplicate profile name is refused case-insensitively`() {
        profiles.create("Transcriber", draft(), enabled = true)

        assertFailsWith<DuplicateOcrProfileNameException> {
            profiles.create("transcriber", draft(), enabled = true)
        }
    }

    @Test
    fun `a profile stores the variable name and only reports whether it is set`() {
        val created = profiles.create(
            "Transcriber",
            draft(apiKeyEnvironmentVariable = "INFOSCRY_TEST_PRESENT_KEY"),
            enabled = true,
        )
        val absent = profiles.create(
            "Reviewer",
            draft(apiKeyEnvironmentVariable = "INFOSCRY_TEST_ABSENT_KEY"),
            enabled = true,
        )
        val lookup: (String) -> String? = { name ->
            "value-of-$name".takeIf { name == "INFOSCRY_TEST_PRESENT_KEY" }
        }
        val keys = OcrProfileService(store, lookup)

        assertTrue(keys.keyAvailable(created.revision))
        assertFalse(keys.keyAvailable(absent.revision))
        assertFalse(
            keys.keyAvailable(created.revision.copy(apiKeyEnvironmentVariable = null)),
            "a loopback profile may name no variable at all",
        )
        assertFalse(profiles.keyAvailable(created.revision), "this process has no such variable set")
        assertEquals(
            "INFOSCRY_TEST_PRESENT_KEY",
            store.findRevision(created.revision.revisionId)!!.apiKeyEnvironmentVariable,
            "the row holds the name, never the value",
        )

        // A capability measurement is read back as the measurement it is, not as a declared switch.
        database.transaction { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "UPDATE ocr_profile_revisions SET image_capability_measured = 1, " +
                        "image_capability_checked_at = '2026-09-30T09:00:00Z' " +
                        "WHERE revision_id = '${created.revision.revisionId}'",
                )
            }
        }
        val measured = store.findRevision(created.revision.revisionId)!!
        assertTrue(measured.imageCapabilitySupported)
        assertEquals("2026-09-30T09:00:00Z", measured.imageCapabilityCheckedAt)
        assertFalse(created.revision.imageCapabilitySupported, "a fresh revision has measured nothing yet")
    }

    // ---- Endpoint classification ----

    @Test
    fun `an endpoint is local only when it is loopback and blank is the provider's external default`() {
        assertEquals(OcrEndpointScope.LOCAL, endpointScope("http://localhost:11434/v1"))
        assertEquals(OcrEndpointScope.LOCAL, endpointScope("http://127.0.0.1:1234/v1"))
        assertEquals(OcrEndpointScope.LOCAL, endpointScope("http://[::1]:8080/v1"))

        // A blank endpoint is the provider's own public destination: never implicitly local.
        assertEquals(OcrEndpointScope.EXTERNAL, endpointScope(""))
        assertEquals(OcrEndpointScope.EXTERNAL, endpointScope("https://api.example.com/v1"))
        assertEquals(OcrEndpointScope.EXTERNAL, endpointScope("http://192.168.1.10:11434/v1"))
        assertEquals(OcrEndpointScope.EXTERNAL, endpointScope("https://localhost.example.com/v1"))
    }

    @Test
    fun `a collection's OCR settings refuse a negative allowance and a mismatched engine`() {
        assertFailsWith<IllegalArgumentException> { collectionSettings().copy(externalPageLimit = -1) }
        assertFailsWith<IllegalArgumentException> { collectionSettings().copy(language = " ") }
        assertFailsWith<IllegalArgumentException> { collectionSettings().copy(engine = OcrEngine.LLM) }
        assertFailsWith<IllegalArgumentException> { collectionSettings().copy(transcriptionProfileId = "profile-a") }
        assertEquals(
            OcrEndpointScope.EXTERNAL,
            profiles.create("Scoped", draft(endpoint = "https://api.example.com/v1"), enabled = true).revision.scope,
        )
    }

    // ---- Fixtures ----

    /** An engine that is only ever asked what its runtime is: this build reads no page through it. */
    private class RuntimeProbe(val identity: String?) : PageOcrEngine {

        override val engine: OcrEngine = OcrEngine.SURYA

        /** How many times this engine was asked to describe itself. */
        var probes: Int = 0
            private set

        override suspend fun runtimeIdentity(): String? {
            probes++
            return identity
        }

        override suspend fun transcribe(page: PageImage, settings: OcrSettingsSnapshot): OcrPageResult =
            error("a runtime probe is never asked to read a page")
    }

    private fun attemptIdentity() = OcrAttemptIdentity(
        engine = OcrEngine.TESSERACT,
        language = "eng",
        transcriptionPromptVersion = OCR_TRANSCRIPTION_PROMPT_VERSION,
        extractorSchemaVersion = "1",
        toolVersion = "tesseract 5.3.0",
        renderDpi = 300,
    )

    private fun draft(
        model: String = "vision-model",
        endpoint: String = "",
        contextWindow: Int = 32_000,
        maxOutputTokens: Int = 4_096,
        inputPricePerMillion: Double = 0.0,
        outputPricePerMillion: Double = 0.0,
        apiKeyEnvironmentVariable: String? = null,
    ) = OcrProfileRevisionDraft(
        provider = LlmProvider.OPENAI_COMPATIBLE,
        endpoint = endpoint,
        model = model,
        contextWindow = contextWindow,
        maxOutputTokens = maxOutputTokens,
        inputPricePerMillion = inputPricePerMillion,
        outputPricePerMillion = outputPricePerMillion,
        apiKeyEnvironmentVariable = apiKeyEnvironmentVariable,
    )

    private fun collectionSettings() = CollectionOcrSettings(language = "eng")

    /** A snapshot of an attempt at one collection's settings, as admission would build it. */
    private fun snapshotFor(engine: OcrEngine, withReviewer: Boolean = false): OcrSettingsSnapshot {
        profileCount++
        val transcriber = profiles.create("Transcriber $profileCount", draft(), enabled = true)
        val reviewer = if (withReviewer) profiles.create("Reviewer $profileCount", draft(), enabled = true) else null
        return profiles.snapshotFor(
            CollectionOcrSettings(
                language = "eng",
                engine = engine,
                transcriptionProfileId = transcriber.id.takeIf { engine == OcrEngine.LLM },
                reviewProfileId = reviewer?.id,
            ),
            extractorVersion = "1",
            toolVersion = if (engine == OcrEngine.TESSERACT) "tesseract 5.3.0" else null,
            renderDpi = 300,
        )
    }

    private fun reviewFingerprint(
        reviewerRevision: String,
        page: String = "1",
        baselineRevisionId: String? = "document-revision-1",
        candidate: String = "shot-1",
        prompt: Int = OCR_REVIEW_PROMPT_VERSION,
        policy: Int = OCR_POLICY_VERSION,
    ) = OcrReviewFingerprint.of(
        documentId = "doc-1",
        unitId = "unit-$page",
        ordinal = page.toInt() - 1,
        baselineRevisionId = baselineRevisionId,
        baselineTextHash = baselineRevisionId?.let { "baseline-hash-$page" },
        candidateHash = candidate,
        reviewProfileRevisionId = reviewerRevision,
        reviewPromptVersion = prompt,
        policyVersion = policy,
    )

    @Test
    fun `a collection row written without OCR settings reads back with the defaults`() {
        val directory = Files.createTempDirectory("infoscry-ocr-defaults")
        try {
            val now = "2026-09-30T07:00:00Z"
            val payload = """{"collectionId":"c1","sources":["/tmp/a.pdf"],"settings":{"ocrLanguages":"deu+eng"}}"""
            Database(directory.resolve("state.db")).use { database ->
                SchemaMigrator(database).migrate()
                database.transaction { connection ->
                    connection.createStatement().use { statement ->
                        statement.execute(
                            "INSERT INTO collections (id, name, ocr_languages, lifecycle, created_at, updated_at) " +
                                "VALUES ('c1', 'Archive', 'deu+eng', 'ACTIVE', '$now', '$now')",
                        )
                        statement.execute(
                            "INSERT INTO jobs (id, collection_id, type, state, payload, created_at, updated_at) " +
                                "VALUES ('j1', 'c1', 'IMPORT', 'QUEUED', '$payload', '$now', '$now')",
                        )
                    }
                }
            }

            Database(directory.resolve("state.db")).use { reopened ->
                val collection = CollectionStore(reopened).get(CollectionId("c1"))!!
                assertEquals("deu+eng", collection.ocrLanguages, "a row keeps the languages it was written with")
                val settings = collection.ocrSettings()
                assertEquals(OcrEngine.TESSERACT, settings.engine)
                assertEquals(OcrImportMode.FILL_MISSING, settings.importMode)
                assertNull(settings.transcriptionProfileId)
                assertNull(settings.reviewProfileId, "review is explicitly unavailable, never an implicit default")
                assertEquals(0, settings.externalPageLimit)

                // A payload without OCR attempt state still reads with its own language.
                val queued = JobStore(reopened).get(JobId("j1"))!!.payload!!
                assertContains(queued, "deu+eng")
                val decoded = ImportJobPayload.decode(queued)
                assertEquals("deu+eng", decoded.settings.ocrLanguages)
                assertNull(decoded.settings.ocrAttempt)
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
