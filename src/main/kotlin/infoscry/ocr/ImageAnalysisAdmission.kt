package infoscry.ocr

import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import kotlinx.serialization.Serializable

/**
 * The image analysis a caller chooses for one run, before any profile is resolved.
 *
 * [profileId] is the profile as the person sees it, not a revision: the revision is resolved at admission so
 * that editing the profile afterwards changes future runs and cannot move an admitted one. `none` is the same
 * as leaving the choice out, which is what the CLI's `--describe-images none` means.
 */
@Serializable
data class ImageAnalysisRequest(
    val profileId: String,
    val identifyAppearance: Boolean = false,
)

/** The chosen image profile cannot be used for this run: it is gone, disabled, unchecked, or unconfirmed. */
class ImageProfileUnavailableException(reason: String) : ApiFaultException(
    ApiStatus.CONFLICT,
    "IMAGE_PROFILE_UNAVAILABLE",
    reason,
)

/** The choice one run admitted: the frozen revision pair, and how many pictures an external one may send. */
data class AdmittedImageAnalysis(
    val choice: ImageAnalysisChoice,
    /** The confirmed allowance for an external profile; zero for a local one, which sends nothing off the machine. */
    val externalAllowance: Int,
)

/**
 * Resolves a run's image-profile choice into the pair its snapshot freezes.
 *
 * Only a profile that passed the real synthetic-image check can describe pictures: a declared image capability
 * is not a measurement, and an unchecked profile would fail at the first picture instead of here. An external
 * profile is only admitted with the number of pictures the dialog showed, because that number is the allowance
 * the run may not exceed; a run whose picture count nobody could state cannot be confirmed and is refused. A
 * local profile needs no allowance: nothing it does leaves this machine.
 */
object ImageAnalysisAdmission {

    /** The token count one picture's analysis is assumed to cost, for the dialog's estimate. */
    const val ASSUMED_TOKENS_PER_IMAGE: Int = 1_200

    fun admit(
        request: ImageAnalysisRequest?,
        profileOf: (String) -> OcrProfile?,
        confirmedImages: Int?,
    ): AdmittedImageAnalysis? {
        if (request == null || request.profileId.isBlank() || request.profileId == NONE) return null
        val revision = requireCapable(request.profileId, profileOf)
        val allowance = if (revision.scope == OcrEndpointScope.EXTERNAL) {
            confirmedImages ?: throw ImageProfileUnavailableException(
                "the number of pictures this run would send could not be determined, so sending them to this " +
                    "profile was not confirmed; choose a profile on this machine or a run whose pictures are counted",
            )
        } else {
            0
        }
        return AdmittedImageAnalysis(
            choice = ImageAnalysisChoice(revision.revisionId, request.identifyAppearance),
            externalAllowance = allowance,
        )
    }

    /** The revision of a profile that may describe pictures: present, enabled, and measured as able to read one. */
    fun requireCapable(profileId: String, profileOf: (String) -> OcrProfile?): OcrProfileRevision {
        val profile = profileOf(profileId)
            ?: throw ImageProfileUnavailableException("the image profile is no longer available; choose another one")
        if (!profile.enabled) {
            throw ImageProfileUnavailableException("the image profile is disabled; choose another one")
        }
        val revision = profile.revision
        if (!revision.imageCapabilitySupported) {
            throw ImageProfileUnavailableException(
                "the image profile has not passed the image check, so it cannot describe pictures; check it again " +
                    "or choose another profile",
            )
        }
        return revision
    }

    /** The estimate for describing [images] pictures through [revision], or null when no estimate can be made. */
    fun costEstimateOf(images: Int?, revision: OcrProfileRevision?): OcrCostEstimate? {
        if (images == null || revision == null) return null
        if (revision.scope != OcrEndpointScope.EXTERNAL) return null
        if (revision.inputPricePerMillion <= 0.0 || revision.outputPricePerMillion <= 0.0) return null
        val perMillion = revision.inputPricePerMillion + revision.outputPricePerMillion
        return OcrCostEstimate(
            amountUsd = images * ASSUMED_TOKENS_PER_IMAGE * perMillion / 1_000_000.0,
            basis = "$images pictures at an assumed $ASSUMED_TOKENS_PER_IMAGE tokens each, priced at " +
                "$perMillion per million tokens",
        )
    }

    /** How the CLI and the routes spell "describe no pictures". */
    const val NONE: String = "none"
}
