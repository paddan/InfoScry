package infoscry.image.analysis

import infoscry.ocr.ExternalPageAllowanceExceededException
import infoscry.ocr.ImageLlmClient
import infoscry.ocr.ImageLlmException
import infoscry.ocr.OcrDispatchAuthority
import infoscry.ocr.OcrProfileRevision
import infoscry.ocr.PageDispatchIdentity
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.math.max

/** The version of the shipped image-analysis instructions; an analysis records the version that produced it. */
const val IMAGE_ANALYSIS_PROMPT_VERSION: Int = 1

/**
 * One picture to describe, with everything the analysis depends on.
 *
 * [caption] and [context] are untrusted source data: the caption the document gave the picture and the page
 * text around it. [language] is the collection's language. [identifyAppearance] is the run's choice — when it is
 * false the model is told to name only what the text states, and the rules drop any appearance-derived name.
 * [dispatch] is the attempt's permit authority, passed through to the client so an external destination is
 * counted against the run's confirmed allowance.
 */
data class AnalysisRequest(
    val identity: PageDispatchIdentity,
    val image: Path,
    val caption: String?,
    val context: String?,
    val language: String,
    val identifyAppearance: Boolean,
    val profile: OcrProfileRevision,
    val dispatch: OcrDispatchAuthority?,
)

/**
 * Describes and tags one picture through an image-capable LLM profile, and validates the answer.
 *
 * The picture is read from its written artifact, reduced to at most [MAX_SIDE_PX] on its longest side with a
 * bounded decode, and sent as PNG (or JPEG when it has no transparency) so a four-thousand-pixel source costs
 * a bounded request. The caption and page text reach the model only inside a delimited untrusted-data block and
 * the shipped prompt names the collection's language and the appearance choice. Every provider failure is this
 * image's outcome, not its document's: a refusal, a timeout or a malformed answer is a [ParsedAnalysis.Invalid]
 * with the code the run reports as a warning.
 *
 * @param clientFor builds the client for one profile revision, with the attempt's dispatch authority as its
 *   permit validator. The client is closed after the call.
 */
class ImageAnalyzer(
    private val clientFor: (OcrProfileRevision, OcrDispatchAuthority?) -> ImageLlmClient,
) {

    suspend fun analyze(request: AnalysisRequest): ParsedAnalysis {
        val prepared = try {
            prepare(request.image)
        } catch (_: IOException) {
            null
        } catch (_: RuntimeException) {
            null
        } ?: return ParsedAnalysis.Invalid(AnalysisRules.INVALID)

        val instructions = ImageAnalysisPrompt.forRequest(
            language = request.language,
            identifyAppearance = request.identifyAppearance,
            caption = request.caption,
            context = request.context,
        )
        val answer = try {
            clientFor(request.profile, request.dispatch).use { client ->
                client.describe(request.identity, prepared.bytes, prepared.mediaType, instructions)
            }
        } catch (exceeded: ExternalPageAllowanceExceededException) {
            // The confirmed allowance is a bound, not a failed image: sending one more would cross it.
            throw exceeded
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (failure: ImageLlmException) {
            return ParsedAnalysis.Invalid(codeOf(failure))
        } catch (_: RuntimeException) {
            // The client refuses to be built for some profiles (an external destination with no permit), and a
            // profile that cannot dispatch is this image's failure rather than a reason to stop the document.
            return ParsedAnalysis.Invalid(AnalysisRules.REFUSED)
        }
        return AnalysisRules.parse(
            answer = answer.value,
            context = sourceTextOf(request.caption, request.context),
            identifyAppearance = request.identifyAppearance,
        )
    }

    /**
     * The provider failure as one of the analysis codes.
     *
     * Only three codes exist because a failed image is retried as a whole, not repaired per failure kind: a
     * timeout is its own outcome (a paid call whose answer nobody saw), an answer that is not the schema is an
     * invalid analysis, and everything the provider refused or could not answer is a refusal.
     */
    private fun codeOf(failure: ImageLlmException): String = when (failure.code) {
        ImageLlmException.TIMEOUT -> AnalysisRules.TIMEOUT
        ImageLlmException.MALFORMED_RESPONSE,
        ImageLlmException.TRUNCATED,
        ImageLlmException.INCOMPLETE,
        ImageLlmException.TOOL_CALL_REFUSED,
        ImageLlmException.WRONG_PAGE,
        ImageLlmException.RESPONSE_TOO_LARGE,
        ImageLlmException.IMAGE_CHANGED,
        ImageLlmException.IMAGE_UNREADABLE,
        ImageLlmException.IMAGE_TOO_LARGE,
        ImageLlmException.IMAGE_DIMENSIONS_UNVERIFIED,
        -> AnalysisRules.INVALID
        else -> AnalysisRules.REFUSED
    }

    /** The source text a `TEXT` name has to be found in: the caption and the page text, as the model saw them. */
    private fun sourceTextOf(caption: String?, context: String?): String =
        listOfNotNull(caption?.takeIf { it.isNotBlank() }, context?.takeIf { it.isNotBlank() }).joinToString("\n")

    private class Prepared(val bytes: ByteArray, val mediaType: String)

    /**
     * The sendable form of [path]: the first frame decoded with a subsampling step that keeps the raster within
     * [MAX_SIDE_PX] on its longest side, then encoded as PNG, or as JPEG when it has no transparency.
     */
    private fun prepare(path: Path): Prepared {
        val raster = decodeBounded(path)
        val opaque = !raster.colorModel.hasAlpha()
        val encoded = raster.toOpaqueIfNeeded(opaque)
        val output = ByteArrayOutputStream()
        val format = if (opaque) "jpg" else "png"
        if (!ImageIO.write(encoded, format, output)) {
            throw IOException("no $format writer is available")
        }
        return Prepared(output.toByteArray(), if (opaque) "image/jpeg" else "image/png")
    }

    /**
     * Decodes the first frame, subsampled so the result is within [MAX_SIDE_PX] on its longest side.
     *
     * The header is read before any raster is allocated, which is what keeps a huge picture from being decoded
     * whole just to be reduced: a 4,000-pixel source is read at a third of its width, not at full size.
     */
    private fun decodeBounded(path: Path): BufferedImage {
        val input = ImageIO.createImageInputStream(path.toFile()) ?: throw IOException("no image reader takes it")
        input.use { stream ->
            val readers = ImageIO.getImageReaders(stream)
            if (!readers.hasNext()) throw IOException("no image reader takes it")
            val reader = readers.next()
            try {
                reader.input = stream
                val width = reader.getWidth(0)
                val height = reader.getHeight(0)
                val step = max(1, ceil(max(width, height).toDouble() / MAX_SIDE_PX).toInt())
                val parameter = reader.defaultReadParam
                if (step > 1) parameter.setSourceSubsampling(step, step, 0, 0)
                return reader.read(0, parameter)
            } catch (unreadable: IOException) {
                throw unreadable
            } catch (unreadable: RuntimeException) {
                // A decoder given untrusted bytes can fail with any runtime exception.
                throw IOException(unreadable)
            } finally {
                reader.dispose()
            }
        }
    }

    /**
     * [image] flattened onto white when it is to be written as JPEG.
     *
     * JPEG has no alpha channel, and a transparent source drawn onto a default black raster would turn a
     * picture's background black in the request.
     */
    private fun BufferedImage.toOpaqueIfNeeded(jpeg: Boolean): BufferedImage {
        if (!jpeg || !colorModel.hasAlpha()) return this
        val flat = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        val graphics = flat.createGraphics()
        try {
            graphics.color = Color.WHITE
            graphics.fillRect(0, 0, width, height)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            graphics.drawImage(this, 0, 0, null)
        } finally {
            graphics.dispose()
        }
        return flat
    }

    companion object {
        /** The longest side of the image one analysis sends, in pixels. */
        const val MAX_SIDE_PX: Int = 1_568

        /** The classpath resource the analysis instructions live in. */
        const val PROMPT_RESOURCE: String = "prompts/image-analysis.txt"
    }
}

/**
 * The shipped analysis instructions, rendered for one picture.
 *
 * The body is a versioned resource, like the transcription prompt: what an analysis was asked is part of what
 * it is, and [IMAGE_ANALYSIS_PROMPT_VERSION] travels in every analysis key.
 */
object ImageAnalysisPrompt {

    private val body: String by lazy {
        ImageAnalysisPrompt::class.java.classLoader.getResourceAsStream(ImageAnalyzer.PROMPT_RESOURCE)
            ?.use { stream -> stream.readBytes().decodeToString() }
            ?: error("prompt resource ${ImageAnalyzer.PROMPT_RESOURCE} is missing from the classpath")
    }

    /** The shipped body with its placeholders unsubstituted. */
    fun body(): String = body

    /** The instructions for one picture: its language, its appearance choice, and its untrusted source text. */
    fun forRequest(language: String, identifyAppearance: Boolean, caption: String?, context: String?): String {
        val appearance = if (identifyAppearance) {
            "You may also name specific people, objects, animals and places that you recognise from their " +
                "appearance, with source APPEARANCE. Only name what you are confident about: these names are " +
                "shown to people as uncertain."
        } else {
            "Do not name anything from its appearance. A name has source TEXT only when the caption or page " +
                "text states it; otherwise omit the name."
        }
        val source = buildString {
            caption?.takeIf { it.isNotBlank() }?.let { append("Caption: ").append(it.trim()).append('\n') }
            context?.takeIf { it.isNotBlank() }?.let { append("Page text: ").append(it.trim()).append('\n') }
        }.trimEnd()
        val safeSource = source.replace("</untrusted-source-text>", "</untrusted-source text>")
        return body
            .replace("{language}", language)
            .replace("{appearance}", appearance)
            .replace("{sourceText}", safeSource)
    }
}
