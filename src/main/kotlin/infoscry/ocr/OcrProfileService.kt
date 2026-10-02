package infoscry.ocr

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
    fun update(id: String, name: String, draft: OcrProfileRevisionDraft, enabled: Boolean): OcrProfile =
        profiles.update(id, name, draft, enabled)
            ?: throw NoSuchElementException("no OCR profile with id $id")

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
