package infoscry.ocr

import infoscry.llm.LlmProvider
import infoscry.llm.ValidEnvironmentVariableName
import infoscry.llm.endpointCarriesUserInfo
import java.net.URI
import java.net.URISyntaxException
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale
import kotlinx.serialization.Serializable

/**
 * How a page image is read.
 *
 * The three engines are one field because they are alternatives for the same work: Tesseract is the
 * installed tool that reads a rendered page, Surya is the local model, and LLM transcribes the image
 * through a configured profile. They are not a fallback chain — nothing switches engines on failure — so
 * an attempt records exactly one of them.
 */
@Serializable
enum class OcrEngine {
    TESSERACT,
    SURYA,
    LLM,
}

/**
 * What an attempted reading is for.
 *
 * Fill-missing keeps usable text a document already carries and invokes the engine where text is absent
 * or unusable; check-and-improve reads page images even when a text layer exists and compares the two.
 * The mode is a collection default and is snapshotted per attempt, because it decides what the engine is
 * asked for rather than which engine is asked.
 */
@Serializable
enum class OcrImportMode {
    FILL_MISSING,
    CHECK_AND_IMPROVE,
    READ_ALL,
}

/**
 * Whether an OCR profile's destination is this machine or leaves it.
 *
 * Selecting an external profile is an explicit decision to send page images and text off the Mac, so the
 * classification is a fact the caller is shown rather than something derived from a hostname by eye.
 */
@Serializable
enum class OcrEndpointScope {
    LOCAL,
    EXTERNAL,
}

/** Which of a collection's two image-model slots a profile is being selected for. */
enum class OcrProfileRole {
    TRANSCRIPTION,
    REVIEW,
}

/**
 * The version of the shipped transcription prompt body.
 *
 * The prompt is part of what a reading is: two readings of the same page under different instructions are
 * not the same evidence, so the version travels in the attempt identity and in its fingerprint. The
 * shipped bodies themselves arrive with the image-model client; what is fixed here is the numbering, so a
 * later prompt edit is a new version rather than a silent change to what an old snapshot meant.
 */
const val OCR_TRANSCRIPTION_PROMPT_VERSION = 1

/**
 * The version of the shipped review prompt body, for the same reason as the transcription version.
 *
 * Version 2 asks the reviewer which of the two request sides the image supports (`A_BETTER`/`B_BETTER`)
 * instead of naming a side as the published or the candidate's one, so a comparison made under version 1 was
 * asked a question that disclosed which side was which and is not this build's comparison.
 */
const val OCR_REVIEW_PROMPT_VERSION = 2

/**
 * The version of the decision policy: which reviewer recommendations may replace text without a person.
 *
 * Version 1 is pilot mode, where a differing candidate always needs manual approval. A measured policy
 * gets a new number, so text published under one policy is never attributed to another.
 */
const val OCR_POLICY_VERSION = 1

/**
 * Whether [endpoint] is on this machine or leaves it.
 *
 * A blank endpoint means the provider's own default destination, which is its public API — external, and
 * never treated as local just because nothing was configured. The host set is read from the URI rather
 * than from a substring of the address: `localhost.example.com` is a remote host that merely starts with
 * the word `localhost`.
 */
fun endpointScope(endpoint: String): OcrEndpointScope {
    val host = try {
        URI(endpoint).host?.trimStart('[')?.trimEnd(']')?.lowercase(Locale.ROOT)
    } catch (_: URISyntaxException) {
        null
    }
    return if (host != null && host in LOOPBACK_HOSTS) OcrEndpointScope.LOCAL else OcrEndpointScope.EXTERNAL
}

private val LOOPBACK_HOSTS: Set<String> = setOf("localhost", "127.0.0.1", "::1")

/**
 * The fields one OCR profile edit supplies.
 *
 * Separate from [OcrProfileRevision] because a revision's identity and sequence belong to the store: a
 * caller must not be able to claim a revision id or a sequence number, and a revision that named its own
 * position could overwrite another row's.
 */
@Serializable
data class OcrProfileRevisionDraft(
    val provider: LlmProvider,
    val model: String,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val endpoint: String = "",
    val inputPricePerMillion: Double = 0.0,
    val outputPricePerMillion: Double = 0.0,
    val apiKeyEnvironmentVariable: String? = null,
) {
    init {
        requireProfileFields(
            typeName = "OcrProfileRevisionDraft",
            endpoint = endpoint,
            model = model,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            inputPricePerMillion = inputPricePerMillion,
            outputPricePerMillion = outputPricePerMillion,
            apiKeyEnvironmentVariable = apiKeyEnvironmentVariable,
        )
    }
}

/**
 * One immutable revision of an OCR profile.
 *
 * A profile is edited by adding a revision, never by rewriting one, because an attempt that ran under
 * revision A has to keep describing revision A: its endpoint, model, limits and prices are what it
 * dispatched to and what it cost. [apiKeyEnvironmentVariable] is the *name* of the variable a key is read
 * from; no key value is stored or returned anywhere, and whether the variable is set is resolved at read
 * time through an injected lookup. [imageCapabilityMeasured] is a measurement, not a declaration: it stays
 * null until a real synthetic-image check passed.
 */
@Serializable
data class OcrProfileRevision(
    val revisionId: String,
    val profileId: String,
    val sequence: Int,
    val provider: LlmProvider,
    val model: String,
    val contextWindow: Int,
    val maxOutputTokens: Int,
    val inputPricePerMillion: Double,
    val outputPricePerMillion: Double,
    val createdAt: String,
    val endpoint: String = "",
    val apiKeyEnvironmentVariable: String? = null,
    val imageCapabilityMeasured: Boolean? = null,
    val imageCapabilityCheckedAt: String? = null,
) {
    init {
        require(revisionId.isNotBlank()) { "OcrProfileRevision.revisionId must not be blank" }
        require(profileId.isNotBlank()) { "OcrProfileRevision.profileId must not be blank" }
        require(sequence > 0) { "OcrProfileRevision.sequence must be positive, was $sequence" }
        require(createdAt.isNotBlank()) { "OcrProfileRevision.createdAt must not be blank" }
        requireProfileFields(
            typeName = "OcrProfileRevision",
            endpoint = endpoint,
            model = model,
            contextWindow = contextWindow,
            maxOutputTokens = maxOutputTokens,
            inputPricePerMillion = inputPricePerMillion,
            outputPricePerMillion = outputPricePerMillion,
            apiKeyEnvironmentVariable = apiKeyEnvironmentVariable,
        )
    }

    /** Whether dispatching to this revision would send page images off this machine. */
    val scope: OcrEndpointScope get() = endpointScope(endpoint)

    /** Whether a real synthetic-image check measured this revision as able to read an image. */
    val imageCapabilitySupported: Boolean get() = imageCapabilityMeasured == true

    /** Whether the named environment variable is set, without ever reading or returning its value. */
    fun keyAvailable(lookup: (String) -> String?): Boolean =
        apiKeyEnvironmentVariable?.let { lookup(it) != null } == true
}

/**
 * A named OCR profile and the immutable revision it currently points at.
 *
 * The profile is the identity a collection selects and the name a person recognises; everything that
 * describes how an attempt reads is in [revision]. An edit repoints [revision] to a new row, which is why
 * a consumer that has a revision id never needs to resolve a profile again.
 */
@Serializable
data class OcrProfile(
    val id: String,
    val name: String,
    val enabled: Boolean,
    val revision: OcrProfileRevision,
    /** The LLM profile this profile was copied from, or null. Not a foreign key: deleting that LLM profile leaves the copy and a dangling id. */
    val sourceLlmProfileId: String? = null,
) {
    init {
        require(id.isNotBlank()) { "OcrProfile.id must not be blank" }
        require(name.isNotBlank()) { "OcrProfile.name must not be blank" }
        require(revision.profileId == id) { "OcrProfile.revision must belong to the profile that carries it" }
    }
}

/**
 * One collection's OCR settings: the language list and default method used to prefill future starts.
 * A profile id is a selection, not a reading: each admitted attempt snapshots the profile revision it chose.
 */
@Serializable
data class CollectionOcrSettings(
    val language: String,
    val defaultMethod: ReadingMethod = ReadingMethod.Tesseract,
) {
    init {
        requireOcrLanguages(language)
    }
}

/**
 * The OCR languages a collection may be set to: trimmed, non-blank, and on one line.
 *
 * The value reaches Tesseract as a `+`-joined list and, more importantly, reaches the fingerprint of every
 * reading the collection produces — and a fingerprint is line-delimited, so a value carrying a line of its
 * own could compose the same lines as another attempt's settings and two different readings would share one
 * fingerprint. The fingerprint refuses such a value itself, which is the last line of defence rather than
 * the first: by then the value is already stored against a collection and every later attempt fails
 * somewhere nobody can see the cause. Refusing it where settings are written makes the failure immediate
 * and attributable to the field a person typed.
 *
 * A line break that *trims* away is not a line break in the stored value, so the check is on the trimmed
 * value — which is also what is written down.
 */
fun requireOcrLanguages(languages: String): String {
    val trimmed = languages.trim()
    require(trimmed.isNotEmpty()) { "collection ocr languages must not be blank" }
    require(trimmed.none { character -> character == '\n' || character == '\r' }) {
        "collection ocr languages must not contain a line break; a language list is written on one line, " +
            "like 'eng+swe'"
    }
    return trimmed
}

/**
 * The part of an attempt that decides whether committed page text may be reused.
 *
 * It is a projection of the [OcrSettingsSnapshot] rather than the snapshot itself, because the snapshot
 * also records the reviewer and a reviewer edit must not invalidate transcription. [renderDpi] is the
 * resolution the attempt actually rendered at, which is the attempt's own fact: a rescan may render at a
 * resolution its import job never used, and the two readings are then not comparable. Absent tool, model
 * and revision fields are part of the identity — a reading whose tool nobody recorded must not compare
 * equal to one that named its tool.
 *
 * [pictureDecodingPolicyVersion] is here because an attempt's checkpoints are keyed by the extraction
 * fingerprint and by nothing else, so what the fingerprint does not cover is reusable across attempts that
 * differ in it. This is the build's version of the rule that decides which pixels of a picture a reading is
 * made from ([PageImage.PICTURE_DECODING_POLICY_VERSION]): under a different rule the same picture is
 * decoded differently — read as itself, or reduced to a bounded copy — and a page committed under one rule
 * is not a reading of the pixels the other would have produced. It defaults to the running build's own rule,
 * which is the only honest answer for an identity that never named one.
 *
 * [runtimeIdentity] is what the *engine's runtime* was discovered to be before a page was read
 * ([PageOcrEngine.runtimeIdentity]): the installed tool's version, its backend, the checkpoint and the
 * cached weights with their revisions and sizes, and the inference server's build. It is deliberately not
 * the same field as [modelVersion]: a model version is what a *reading* reported about itself while a page
 * was being read, and this is what the attempt knew before it read anything — which is the only form that
 * can be part of the fingerprint a checkpoint is looked up by. Absent means "no runtime identity was
 * discovered", and it is part of the identity: an attempt whose runtime could not be described may not
 * compare equal to one that named its runtime, or a checkpoint written under unknown weights would be
 * reused under them.
 */
@Serializable
data class OcrAttemptIdentity(
    val engine: OcrEngine,
    val mode: OcrImportMode = OcrImportMode.READ_ALL,
    val language: String,
    val transcriptionPromptVersion: Int,
    val extractorSchemaVersion: String,
    val profileRevisionId: String? = null,
    val toolVersion: String? = null,
    val modelVersion: String? = null,
    val renderDpi: Int? = null,
    val pictureDecodingPolicyVersion: Int = PageImage.PICTURE_DECODING_POLICY_VERSION,
    val runtimeIdentity: String? = null,
) {
    init {
        require(pictureDecodingPolicyVersion > 0) {
            "OcrAttemptIdentity.pictureDecodingPolicyVersion must be positive, was " +
                pictureDecodingPolicyVersion
        }
        require(language.isNotBlank()) { "OcrAttemptIdentity.language must not be blank" }
        require(transcriptionPromptVersion > 0) {
            "OcrAttemptIdentity.transcriptionPromptVersion must be positive, was $transcriptionPromptVersion"
        }
        require(extractorSchemaVersion.isNotBlank()) {
            "OcrAttemptIdentity.extractorSchemaVersion must not be blank"
        }
        require((engine == OcrEngine.LLM) == (profileRevisionId != null)) {
            "engine LLM reads through a profile revision, and a local engine must not name one"
        }
    }
}

/**
 * Everything one OCR attempt was admitted with, as one immutable value.
 *
 * It is what a rescan job carries and what a resumed attempt is compared against: the collection's
 * settings, the profile *revisions* those settings pointed at, the versions of the machine that ran it,
 * and the policy that decides what may be replaced. It deliberately holds no key, no path and no document
 * text, so it can be persisted and logged without leaking anything.
 *
 * [autoValidationId] names the accepted validation record that allows automatic replacement. It stays null
 * in pilot mode, where a differing candidate always needs manual approval.
 *
 * [runtimeIdentity] is what the engine's runtime was probed to be *before* the attempt read a page
 * ([PageOcrEngine.runtimeIdentity]). It is part of the persisted snapshot rather than of a reading because a
 * resumed attempt must not silently reuse a different runtime: weights, a tool version or an inference
 * server build that changed between two attempts makes their readings different evidence, and a page already
 * read under the first one may not be reused under the second. Absent means the engine described no runtime,
 * which is a different identity from any discovered one.
 */
@Serializable
data class OcrSettingsSnapshot(
    val engine: OcrEngine,
    val mode: OcrImportMode,
    val language: String,
    val extractorVersion: String,
    val transcriptionPromptVersion: Int = OCR_TRANSCRIPTION_PROMPT_VERSION,
    val reviewPromptVersion: Int = OCR_REVIEW_PROMPT_VERSION,
    val policyVersion: Int = OCR_POLICY_VERSION,
    val externalPageLimit: Int = 0,
    /** External import allowance covers only the unchanged files in its confirmed source manifest. */
    val externalConfirmedSourceScope: Boolean = false,
    val transcriptionProfileRevisionId: String? = null,
    val reviewProfileRevisionId: String? = null,
    val toolVersion: String? = null,
    val modelVersion: String? = null,
    val renderDpi: Int? = null,
    val autoValidationId: String? = null,
    val runtimeIdentity: String? = null,
) {
    init {
        require(language.isNotBlank()) { "OcrSettingsSnapshot.language must not be blank" }
        require(extractorVersion.isNotBlank()) { "OcrSettingsSnapshot.extractorVersion must not be blank" }
        require(transcriptionPromptVersion > 0) {
            "OcrSettingsSnapshot.transcriptionPromptVersion must be positive, was $transcriptionPromptVersion"
        }
        require(reviewPromptVersion > 0) {
            "OcrSettingsSnapshot.reviewPromptVersion must be positive, was $reviewPromptVersion"
        }
        require(policyVersion > 0) { "OcrSettingsSnapshot.policyVersion must be positive, was $policyVersion" }
        require(externalPageLimit >= 0) {
            "the external page allowance must not be negative, was $externalPageLimit"
        }
        require((engine == OcrEngine.LLM) == (transcriptionProfileRevisionId != null)) {
            "engine LLM reads through a profile revision, and a local engine must not name one"
        }
    }

    /** The reading's own identity, which is what extraction reuse is keyed by. */
    fun attemptIdentity(): OcrAttemptIdentity = OcrAttemptIdentity(
        engine = engine,
        mode = mode,
        language = language,
        transcriptionPromptVersion = transcriptionPromptVersion,
        extractorSchemaVersion = extractorVersion,
        profileRevisionId = transcriptionProfileRevisionId,
        toolVersion = toolVersion,
        modelVersion = modelVersion,
        renderDpi = renderDpi,
        runtimeIdentity = runtimeIdentity,
    )

    companion object {

        /**
         * The snapshot of one attempt, built from the settings, the profile revisions those settings named
         * and the versions the machine reported.
         *
         * Pure by design: it reads nothing, so a profile edited a moment later cannot change an attempt
         * that was already snapshotted, and an admission path can build one inside a test without a store.
         * Resolving a profile id to the revision it currently points at is the caller's step, because that
         * is the read that has to happen once, under admission.
         */
        fun of(
            settings: CollectionOcrSettings,
            extractorVersion: String,
            transcriptionProfileRevisionId: String? = null,
            reviewProfileRevisionId: String? = null,
            toolVersion: String? = null,
            modelVersion: String? = null,
            renderDpi: Int? = null,
            runtimeIdentity: String? = null,
        ): OcrSettingsSnapshot = OcrSettingsSnapshot(
            engine = when (settings.defaultMethod) {
                ReadingMethod.Tesseract -> OcrEngine.TESSERACT
                ReadingMethod.Surya -> OcrEngine.SURYA
                is ReadingMethod.Llm -> OcrEngine.LLM
            },
            mode = OcrImportMode.READ_ALL,
            language = settings.language,
            extractorVersion = extractorVersion,
            transcriptionProfileRevisionId = transcriptionProfileRevisionId,
            reviewProfileRevisionId = reviewProfileRevisionId,
            toolVersion = toolVersion,
            modelVersion = modelVersion,
            renderDpi = renderDpi,
            externalPageLimit = 0,
            runtimeIdentity = runtimeIdentity,
        )
    }
}

/**
 * What decides whether one page's reading may be reused.
 *
 * Only things that can change the text are in it: the page it is about, what read it, and the settings that
 * govern the reading. Re-embedding a passage and changing the reviewer are deliberately absent — neither
 * changes what the page says, so neither may invalidate a reading that is already committed.
 */
@JvmInline
value class OcrTranscriptionFingerprint(val value: String) {

    override fun toString(): String = value

    companion object {

        /** The fingerprint of one page read by one attempt. */
        fun of(
            documentId: String,
            unitId: String,
            ordinal: Int,
            attempt: OcrAttemptIdentity,
        ): OcrTranscriptionFingerprint {
            require(documentId.isNotBlank()) { "a transcription fingerprint needs the document's identity" }
            require(unitId.isNotBlank()) { "a transcription fingerprint needs the page's stable unit id" }
            require(ordinal >= 0) { "a transcription fingerprint needs the page's ordinal, was $ordinal" }
            // A canonical, ordered, line-delimited form: the same attempt always hashes the same, and two
            // identities cannot collide by concatenating into the same string.
            val canonical = listOf(
                "document=$documentId",
                "unit=$unitId",
                "ordinal=$ordinal",
                "engine=${attempt.engine}",
                "mode=${attempt.mode}",
                "profile_revision=${fingerprintPresence(attempt.profileRevisionId)}",
                "language=${attempt.language}",
                "transcription_prompt=${attempt.transcriptionPromptVersion}",
                "tool=${fingerprintPresence(attempt.toolVersion)}",
                "model=${fingerprintPresence(attempt.modelVersion)}",
                "render_dpi=${attempt.renderDpi ?: NONE}",
                "extractor_schema=${attempt.extractorSchemaVersion}",
                "picture_decoding_policy=${attempt.pictureDecodingPolicyVersion}",
                // What the reading was made *with*. A commit under one runtime is not a reading another one
                // produced, and "nobody described the runtime" is not "this runtime": the two are tagged
                // rather than spelled, so a runtime that reports the literal word one of them might have used
                // cannot compose the other's line.
                "runtime=${fingerprintPresence(attempt.runtimeIdentity)}",
            ).let(::fingerprintFields)
            return OcrTranscriptionFingerprint(sha256Of(canonical))
        }

        private const val NONE = "none"
    }
}

/**
 * The fields every OCR profile revision has to satisfy, whether it is a draft or a stored row.
 *
 * Both types check the same rules through one function so a value that passes the store's validation
 * cannot be rejected by the record it is read into, and so the message a caller sees names the same field
 * in both cases.
 */
private fun requireProfileFields(
    typeName: String,
    endpoint: String,
    model: String,
    contextWindow: Int,
    maxOutputTokens: Int,
    inputPricePerMillion: Double,
    outputPricePerMillion: Double,
    apiKeyEnvironmentVariable: String?,
) {
    require(model.isNotBlank()) { "$typeName.model must not be blank" }
    require(contextWindow > 0) { "$typeName.contextWindow must be positive, was $contextWindow" }
    require(maxOutputTokens > 0) { "$typeName.maxOutputTokens must be positive, was $maxOutputTokens" }
    // Non-negative and finite: NaN and infinity are both refused, because a price nobody can compute with
    // is not a price, and a cost estimate built from one would be wrong rather than unknown.
    require(inputPricePerMillion >= 0.0 && inputPricePerMillion.isFinite()) {
        "$typeName.inputPricePerMillion must be a non-negative finite price, was $inputPricePerMillion"
    }
    require(outputPricePerMillion >= 0.0 && outputPricePerMillion.isFinite()) {
        "$typeName.outputPricePerMillion must be a non-negative finite price, was $outputPricePerMillion"
    }
    endpoint.takeIf { it.isNotBlank() }?.let { candidate ->
        val uri = try {
            URI(candidate)
        } catch (failure: URISyntaxException) {
            throw IllegalArgumentException(
                "$typeName.endpoint must be an absolute http or https URL when provided, was a value " +
                    "that is not a URL",
                failure,
            )
        }
        // An absolute URI is not enough: `ftp://host/x` and `mailto:a@b` are absolute too, and this seam only
        // knows how to POST and stream a response. A host is required for the same reason — without one there
        // is nowhere to dispatch to, and accepting it would push the failure to the first paid call.
        require(uri.isAbsolute && uri.scheme?.lowercase() in HTTP_SCHEMES && !uri.host.isNullOrBlank()) {
            "$typeName.endpoint must be an absolute http or https URL when provided, was a value with a " +
                "different scheme or no host"
        }
        // Credentials belong in the environment variable this profile names. A URL that carries them is
        // stored verbatim and returned verbatim by the profile API, so it is refused rather than stripped:
        // silently dropping half of what a caller sent would let a caller believe a credential had been
        // stored. The value is not repeated in the message, which is the other place it would be published.
        require(!endpointCarriesUserInfo(candidate)) {
            "$typeName.endpoint must not carry userinfo credentials; a key belongs in the environment " +
                "variable this profile names, never in the URL"
        }
    }
    apiKeyEnvironmentVariable?.let {
        require(ValidEnvironmentVariableName.matches(it)) {
            "apiKeyEnvironmentVariable must be a valid environment variable name"
        }
    }
}

/** The two schemes the OCR transports can dispatch to; an endpoint naming another one is not selectable. */
private val HTTP_SCHEMES = setOf("http", "https")

/**
 * A fingerprint field that distinguishes "nothing was discovered" from a discovered value.
 *
 * Encoding the two as a tag rather than as a word is what makes them impossible to confuse: a runtime that
 * truthfully reports the literal string `none`, or `absent`, or `present:x`, is a *discovered* identity, and a
 * reading committed while nothing was known must not be reused for it. Every nullable description in a
 * fingerprint uses this form, so no value a tool or a store can produce composes the line absence uses.
 * Fields that are not nullable (a number, a language) need no tag: they have no absent state to confuse.
 */
internal fun fingerprintPresence(value: String?): String =
    if (value == null) "absent" else "present:$value"

/**
 * The canonical, line-delimited form one fingerprint hashes.
 *
 * One field per line is unambiguous only while no value carries a line of its own: a tool version of
 * "x\nmodel=y" with model "z" would compose exactly the lines of tool "x" with model "y\nmodel=z", and two
 * different readings would share one fingerprint — which is what makes a later attempt reuse a checkpoint
 * it did not produce. Every value here is a version, an id, a language or a schema number, none of which a
 * legitimate source produces with a line break in it, so a value that carries one is refused rather than
 * encoded: escaping would leave the two readings comparable as strings while still calling them different.
 */
private fun fingerprintFields(fields: List<String>): String {
    fields.forEach { field ->
        require(field.none { character -> character == '\n' || character == '\r' }) {
            "a fingerprint field may not contain a line break: '${field.substringBefore('=')}' does"
        }
    }
    return fields.joinToString("\n")
}

/** A canonical string's SHA-256, as lowercase hex: the one digest form every fingerprint here uses. */
private fun sha256Of(canonical: String): String =
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8)))
