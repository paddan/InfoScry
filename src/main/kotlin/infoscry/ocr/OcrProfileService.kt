package infoscry.ocr

import infoscry.llm.LlmProfile
import infoscry.llm.ProviderCatalog
import infoscry.llm.ProviderCatalogData
import infoscry.llm.imageInputOf
import infoscry.storage.OcrProfileStore
import infoscry.storage.Instants

/**
 * What one synthetic-image capability check measured, and why it did not pass when it did not.
 *
 * [supported] is the measurement the profile's revision records: a check that ran and did not prove image
 * transport measured `false`, whatever the reason. [errorCode] is the reason, kept out of the record
 * because it is about *this* check rather than about the revision, and [modelVersion] is the model version
 * the provider resolved the profile's alias to, when it reported one.
 */
data class OcrCapabilityMeasurement(
    val supported: Boolean,
    val modelVersion: String? = null,
    val errorCode: String? = null,
)

/** An LLM profile whose model the catalog states cannot read an image, so no OCR profile is made from it. */
class TextOnlyLlmProfileException : IllegalStateException(
    "the model of that LLM profile does not accept image input, so it cannot read page images",
)

/** What choosing an LLM profile for OCR produced: the OCR profile, and whether this call created it. */
data class LlmProfileCopy(val profile: OcrProfile, val created: Boolean)

/** The suffix that marks an OCR profile as the copy of an LLM profile, so the two never share a name. */
const val LLM_COPY_NAME_SUFFIX = " (from LLM profile)"

/**
 * The rules that sit between an OCR profile's stored rows and what may select one.
 *
 * CRUD itself is the store's; what this service adds is the two decisions a caller must not make for
 * itself: whether a profile may be *selected* at all, and which revision an attempt snapshots. Both read
 * once, at the moment of the decision, which is why an attempt that has a revision id never resolves a
 * profile again.
 */
class OcrProfileService(
    private val profiles: OcrProfileStore,
    /**
     * How "the variable is set" is answered. Injected so a test can state the answer instead of depending on
     * the environment its process happens to have, exactly as the LLM profile API does with `System::getenv`.
     */
    private val environment: (String) -> String? = System::getenv,
    /**
     * The external dispatch permit validator of ticket 07, or null while this build has none.
     *
     * It is a constructor parameter rather than something a route may choose, so wiring the admission
     * service into this object is the one place external processing becomes reachable — and nothing in a
     * request can supply a validator of its own.
     */
    private val permits: ExternalDispatchPermitValidator? = null,
    /**
     * How an attempt's reading engine is built, so admission can ask what runtime it would read with.
     *
     * It is the same factory an attempt's page images go through, and null means this build has no engines
     * wired: admission then records no runtime identity, which is a different value from any discovered one
     * and therefore never compares equal to one.
     */
    private val engineFor: ((OcrEngine, OcrSettingsSnapshot, OcrDispatchAuthority?) -> PageOcrEngine?)? = null,
    /** What the curated catalog states about a model's image input, for an LLM profile offered as OCR. */
    private val providerCatalog: ProviderCatalogData = ProviderCatalog.load(),
) {

    fun list(): List<OcrProfile> = profiles.list()

    fun get(id: String): OcrProfile? = profiles.findById(id)

    /** One profile by id, or a failure naming that no such profile exists. */
    fun require(id: String): OcrProfile =
        get(id) ?: throw NoSuchElementException("no OCR profile with id $id")

    fun create(name: String, draft: OcrProfileRevisionDraft, enabled: Boolean): OcrProfile =
        profiles.create(name, draft, enabled)

    /**
     * Edits a profile into a new immutable revision, or fails when no such profile exists.
     *
     * The new revision is what future attempts read; the revision the profile pointed at before is kept, so
     * an attempt that snapshotted it still describes what it ran with.
     */
    fun update(
        id: String,
        name: String,
        draft: OcrProfileRevisionDraft,
        enabled: Boolean,
        expectedRevisionId: String? = null,
    ): OcrProfile =
        profiles.update(id, name, draft, enabled, expectedRevisionId)
            ?: throw NoSuchElementException("no OCR profile with id $id")

    /**
     * Whether the catalog states that [llm]'s model accepts image input: true or false when it says so, null
     * when it does not. Unknown is never turned into either answer here.
     */
    fun imageInputOf(llm: LlmProfile): Boolean? = providerCatalog.imageInputOf(llm.provider, llm.model)

    /**
     * The OCR profile that carries [llm]'s settings, created or brought up to date.
     *
     * An LLM profile is offered for transcription and review by being *copied* into an ordinary OCR profile,
     * the one this build already knows how to pin: an attempt snapshots that profile's immutable revision, so
     * a later edit of the LLM profile changes nothing that was admitted. Choosing the LLM profile again after
     * an edit adds a new revision to the same copy rather than a second profile. The copy carries no
     * measurement of its own: the image capability check runs on it, as on any OCR profile.
     *
     * A model the catalog states is text-only is refused; a model it does not mention is accepted as
     * "unknown" and left to the capability check.
     */
    fun copyOfLlmProfile(llm: LlmProfile): LlmProfileCopy {
        require(llm.enabled) { "that LLM profile is disabled and cannot be offered for OCR" }
        if (imageInputOf(llm) == false) throw TextOnlyLlmProfileException()
        val name = llm.name + LLM_COPY_NAME_SUFFIX
        val draft = OcrProfileRevisionDraft(
            provider = llm.provider,
            model = llm.model,
            contextWindow = llm.contextWindow,
            maxOutputTokens = llm.maxOutputTokens,
            endpoint = llm.endpoint,
            inputPricePerMillion = llm.inputPricePerMillion,
            outputPricePerMillion = llm.outputPricePerMillion,
            apiKeyEnvironmentVariable = llm.apiKeyEnvironmentVariable,
        )
        val existing = profiles.list().firstOrNull { it.name.equals(name, ignoreCase = true) }
            ?: return LlmProfileCopy(profiles.create(name, draft, enabled = true), created = true)
        val current = existing.revision
        val unchanged = existing.enabled &&
            current.provider == draft.provider && current.model == draft.model &&
            current.contextWindow == draft.contextWindow && current.maxOutputTokens == draft.maxOutputTokens &&
            current.endpoint == draft.endpoint &&
            current.apiKeyEnvironmentVariable == draft.apiKeyEnvironmentVariable &&
            current.inputPricePerMillion == draft.inputPricePerMillion &&
            current.outputPricePerMillion == draft.outputPricePerMillion
        if (unchanged) return LlmProfileCopy(existing, created = false)
        val updated = profiles.update(existing.id, name, draft, enabled = true, expectedRevisionId = current.revisionId)
            ?: throw NoSuchElementException("no OCR profile with id ${existing.id}")
        return LlmProfileCopy(updated, created = false)
    }

    /** Disables new use of a profile, keeping its revisions. False when no such profile exists. */
    fun disable(id: String): Boolean = profiles.disable(id)

    /**
     * The profile a collection may select for [role], or a failure.
     *
     * Unknown and disabled profiles are refused the same way and with the same words: an id a caller made
     * up and an id that exists but is retired are both "this is not something you may select", and neither
     * answer tells the caller anything about another collection's settings. The message never repeats the id,
     * because a caller can paste anything into that field and an error is not a place to echo it.
     */
    fun requireSelectable(profileId: String, role: OcrProfileRole): OcrProfile =
        requireSelectable(profiles.findById(profileId), profileId, role)

    /**
     * The same decision for a profile a caller already read, so one snapshot can judge both slots together.
     */
    private fun requireSelectable(profile: OcrProfile?, profileId: String, role: OcrProfileRole): OcrProfile {
        require(profileId.isNotBlank()) { "an OCR ${role.label} profile id must not be blank" }
        val selected = profile ?: throw IllegalArgumentException("no usable OCR ${role.label} profile with that id")
        require(selected.enabled) { "that OCR ${role.label} profile is disabled and cannot be selected" }
        return selected
    }

    /** Whether the revision's named environment variable is set, without reading or returning its value. */
    fun keyAvailable(revision: OcrProfileRevision): Boolean = revision.keyAvailable(environment)

    /**
     * Runs one profile's synthetic-image capability check and records what it measured.
     *
     * The check sends this build's explicit synthetic image and nothing else, through the given revision's
     * own endpoint, model and limits — the same client a page would be read through, asked about a picture
     * that belongs to no document. Two outcomes are deliberately not a measurement: a refusal before
     * anything was sent (no endpoint, no external permit, no credential) is raised to the caller, because a
     * check that never ran says nothing about the revision; and a check the provider answered or failed is
     * recorded as `false` unless it proved transport.
     */
    suspend fun probeImageCapability(revision: OcrProfileRevision): OcrCapabilityMeasurement {
        val client = ImageLlmClient(
            profile = revision,
            lookup = environment,
            permits = permits,
            timeout = ImageLlmClient.PROBE_TIMEOUT,
        )
        val measurement = client.use { probe ->
            try {
                OcrCapabilityMeasurement(supported = true, modelVersion = probe.probeCapability().modelVersion)
            } catch (refused: ImageLlmException) {
                // Nothing left this machine, so there is nothing to record: the caller has to fix the
                // profile or the permit before a check can run at all.
                if (!refused.dispatched) throw refused
                OcrCapabilityMeasurement(supported = false, errorCode = refused.code)
            }
        }
        profiles.recordImageCapability(revision.revisionId, measurement.supported, Instants.now())
        return measurement
    }

    /**
     * The snapshot of one attempt: the revisions the collection's settings name *now*, and the versions of
     * the machine that will run it.
     *
     * Resolving the two profile ids happens here, once, because that is the read the snapshot exists to
     * freeze: editing a profile afterwards must not change an attempt that was already admitted, and a
     * disabled profile must not be resumable as if it were still selectable. A local engine has no
     * transcription profile at all, so its snapshot carries no revision and the reading is identified by the
     * engine and its tool version.
     */
    /**
     * [snapshot] with the runtime identity its reading engine reports, discovered without reading a page.
     *
     * The identity has to be discovered *here*, before the attempt is enqueued, because it is part of what
     * the attempt's fingerprint is computed from and therefore of the key its committed pages are looked up
     * by: an engine that was upgraded between two attempts must not silently be handed the older runtime's
     * pages. Probing at the attempt instead would make the key depend on when the attempt happened to run,
     * so a restart would resume under a newly discovered runtime rather than the recorded one.
     *
     * An engine that cannot describe itself answers null, which is recorded as it stands rather than left
     * absent-and-therefore-guessable-later: the identity a snapshot carries is what the attempt uses, and no
     * later probe replaces it. A build with no engines wired leaves the snapshot untouched.
     */
    suspend fun withProbedRuntime(snapshot: OcrSettingsSnapshot): OcrSettingsSnapshot {
        val engine = engineFor?.invoke(snapshot.engine, snapshot, null) ?: return snapshot
        return snapshot.copy(runtimeIdentity = engine.runtimeIdentity())
    }

    fun snapshotFor(
        settings: CollectionOcrSettings,
        extractorVersion: String,
        toolVersion: String? = null,
        modelVersion: String? = null,
        renderDpi: Int? = null,
    ): OcrSettingsSnapshot {
        // Both slots are read in one transaction before either is refused, so the snapshot freezes one
        // moment even when an edit lands between the two lookups, and a refusal names the slot that was
        // wrong rather than the order the reads happened to run in.
        val selected = profiles.findByIds(
            listOfNotNull(settings.transcriptionProfileId, settings.reviewProfileId).distinct(),
        )
        return OcrSettingsSnapshot.of(
            settings = settings,
            extractorVersion = extractorVersion,
            transcriptionProfileRevisionId = settings.transcriptionProfileId?.let { profileId ->
                requireSelectable(selected[profileId], profileId, OcrProfileRole.TRANSCRIPTION).revision.revisionId
            },
            reviewProfileRevisionId = settings.reviewProfileId?.let { profileId ->
                requireSelectable(selected[profileId], profileId, OcrProfileRole.REVIEW).revision.revisionId
            },
            toolVersion = toolVersion,
            modelVersion = modelVersion,
            renderDpi = renderDpi,
        )
    }
}

/** The word a role is named by in a failure, so a caller is told which of the two slots it got wrong. */
private val OcrProfileRole.label: String
    get() = when (this) {
        OcrProfileRole.TRANSCRIPTION -> "transcription"
        OcrProfileRole.REVIEW -> "review"
    }
