package infoscry.image.analysis

import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.image.WrittenImage
import infoscry.ocr.ImageAnalysisChoice
import infoscry.ocr.OcrDispatchAuthority
import infoscry.ocr.OcrProfileRevision
import infoscry.ocr.PageDispatchIdentity
import infoscry.storage.ImageAnalysisStore
import java.nio.file.Path

/** What one picture contributes to its unit: the text it is indexed with, its categories, and a warning code. */
data class AnalysisResult(
    val text: String,
    val categories: Set<TagCategory>,
    val warning: String?,
)

/**
 * The analysis of one picture inside the image stage, and the text its unit is delivered with.
 *
 * A completed analysis is reused for the same key — the image, the profile revision, the prompt version, the
 * language and the appearance choice — so a repeat or a resumed run makes zero model calls. A failed one is
 * recorded too, and the next run retries it. With no choice for this run, the latest completed analysis of the
 * image is kept, which is what makes an image-only rescan leave tags in place; a picture never analysed is its
 * caption and page context alone.
 *
 * Edits are applied on top of whatever analysis was used, so a removed tag stays out and a confirmed one is
 * stated as fact whatever a later analysis proposes.
 *
 * The text is built by [ImageUnitText] and the model call by [ImageAnalyzer]; this step owns the ordering and the
 * durable record, and nothing here logs or returns a caption, a description or a tag in an error.
 */
class ImageAnalysisStep(
    private val analyzer: ImageAnalyzer,
    private val store: ImageAnalysisStore,
    private val profileRevisionOf: (String) -> OcrProfileRevision?,
) {

    suspend fun textFor(
        document: Document,
        unitId: ContentUnitId,
        image: WrittenImage,
        imagePath: Path,
        caption: String?,
        context: String?,
        choice: ImageAnalysisChoice?,
        language: String,
        dispatch: OcrDispatchAuthority?,
    ): AnalysisResult {
        val analysis = if (choice == null) {
            store.latestDone(document.id, unitId, image.sha256)
        } else {
            storedOrAnalysed(document, unitId, image, imagePath, caption, context, choice, language, dispatch)
        }
        val edited = ImageUnitText.tagsWithEdits(analysis?.tags.orEmpty(), store.edits(document.id, unitId))
        return AnalysisResult(
            text = ImageUnitText.of(caption, analysis?.description, edited, context),
            categories = ImageUnitText.categoriesOf(edited),
            warning = analysis?.takeIf { it.outcome == AnalysisOutcome.FAILED }?.errorCode,
        )
    }

    /** A completed analysis for the choice's key, or one made now and recorded before it is returned. */
    private suspend fun storedOrAnalysed(
        document: Document,
        unitId: ContentUnitId,
        image: WrittenImage,
        imagePath: Path,
        caption: String?,
        context: String?,
        choice: ImageAnalysisChoice,
        language: String,
        dispatch: OcrDispatchAuthority?,
    ): ImageAnalysis {
        val key = ImageAnalysisKey(
            documentId = document.id,
            unitId = unitId,
            imageSha256 = image.sha256,
            profileRevisionId = choice.profileRevisionId,
            promptVersion = IMAGE_ANALYSIS_PROMPT_VERSION,
            language = language,
            identifyAppearance = choice.identifyAppearance,
        )
        store.get(key)?.takeIf { it.outcome == AnalysisOutcome.DONE }?.let { return it }
        val profile = profileRevisionOf(choice.profileRevisionId)
        val analysis = if (profile == null) {
            failed(key, AnalysisRules.REFUSED)
        } else {
            when (
                val parsed = analyzer.analyze(
                    AnalysisRequest(
                        identity = PageDispatchIdentity(unitId = unitId.value, ordinal = 0, documentId = document.id.value),
                        image = imagePath,
                        caption = caption,
                        context = context,
                        language = language,
                        identifyAppearance = choice.identifyAppearance,
                        profile = profile,
                        dispatch = dispatch,
                    ),
                )
            ) {
                is ParsedAnalysis.Valid -> ImageAnalysis(
                    key = key,
                    outcome = AnalysisOutcome.DONE,
                    description = parsed.description,
                    tags = parsed.tags,
                    errorCode = null,
                    caption = caption?.takeIf { it.isNotBlank() },
                    pageContext = context?.takeIf { it.isNotBlank() },
                )
                is ParsedAnalysis.Invalid -> failed(key, parsed.code)
            }
        }
        // Recorded before it is returned: an interruption after this point resumes without paying for the same
        // picture again, and the text the checkpoint commits describes an analysis the store already holds.
        store.record(analysis)
        return analysis
    }

    private fun failed(key: ImageAnalysisKey, code: String) = ImageAnalysis(
        key = key,
        outcome = AnalysisOutcome.FAILED,
        description = null,
        tags = emptyList(),
        errorCode = code,
    )
}
