package infoscry.extract

import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceImageProvenance
import infoscry.domain.SourceLocation
import infoscry.domain.UnitKind
import infoscry.ocr.OCR_TRANSCRIPTION_PROMPT_VERSION
import infoscry.ocr.OcrAttemptIdentity
import infoscry.ocr.OcrEngine
import infoscry.ocr.OcrImportMode
import infoscry.ocr.OcrSettingsSnapshot
import infoscry.ocr.fingerprintPresence
import java.io.IOException
import java.nio.file.Path
import java.security.MessageDigest
import java.util.HexFormat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.serialization.Serializable

/**
 * The version of the extraction contract itself: how units are shaped, what a fingerprint means, and
 * what an extractor is allowed to skip. It is part of every fingerprint, so a change that makes old
 * output incomparable invalidates reuse instead of silently mixing two shapes in one index.
 */
const val EXTRACTOR_SCHEMA_VERSION = "1"

/**
 * The largest text container an extractor will hold in memory in one piece.
 *
 * An extractor that reads a whole document before it can name a citable unit — a DOM, a whole-file
 * string — has no way to split work it does not understand, so above this size it refuses the document
 * instead of risking an out-of-memory kill that would take every other job in the process with it.
 */
internal const val MAX_TEXT_DOCUMENT_BYTES: Long = 32L * 1024 * 1024

/**
 * The code a text container above [MAX_TEXT_DOCUMENT_BYTES] fails under.
 *
 * One spelling on purpose: the code travels into the item outcome, the CLI and the queue, so a reader
 * who sees it has to find the same word everywhere.
 */
internal const val DOCUMENT_TOO_LARGE_CODE: String = "DOCUMENT_TOO_LARGE"

/**
 * The key a document refused before its first unit fails under.
 *
 * A key names a unit, and a document refused before its first unit has none, so such a failure names the
 * document instead. Every extractor that can refuse a document uses this one spelling, because the key is
 * what makes the refusal survive a resume: the next attempt recognises it and does not re-report it.
 */
internal const val DOCUMENT_TOO_LARGE_KEY: String = "document-too-large"

/**
 * The code a document protected by a password or DRM fails under.
 *
 * One spelling on purpose: the PDF reader, the Office readers, and the e-book reader all refuse protected
 * material rather than decrypting it, and a reader who sees this code has to find the same word in the
 * item outcome, the queue, and the CLI.
 *
 * Nothing is decrypted and nothing is guessed: a document that needs a password is a document this
 * pipeline cannot cite, which is what the person importing it needs to be told.
 */
internal const val ENCRYPTED_DOCUMENT_CODE: String = "ENCRYPTED_DOCUMENT"

/**
 * The key a document refused before its first unit fails under.
 *
 * A key names a unit, and a document refused before it has one, so such a refusal names the document
 * instead. The *code* carries the reason (`ENCRYPTED_DOCUMENT`, `DOCUMENT_UNREADABLE`, `NEEDS_TESSERACT`)
 * and this key is what makes any of them survive a resume: the next attempt recognises the refusal and
 * does not report it a second time.
 */
internal const val DOCUMENT_REFUSED_KEY: String = "document"

/**
 * The code a container that cannot be opened at all fails under.
 *
 * The bytes said what the file was, and then nothing could be read from it: a truncated download, a file
 * whose header promised a format its body does not have. One spelling, because it reaches the queue and the
 * CLI the same way every other code does.
 */
internal const val DOCUMENT_UNREADABLE_CODE: String = "DOCUMENT_UNREADABLE"

/**
 * The code a unit whose OCR call failed fails under.
 *
 * The tool ran and could not read *this* unit, which is a different thing from the tool being missing: the
 * rest of the document still delivers, so the failure belongs to the unit rather than to the document. One
 * spelling, because it reaches the queue and the CLI the same way every other code does.
 */
internal const val OCR_FAILED_CODE: String = "OCR_FAILED"

/**
 * The code a unit whose rendered raster is verified blank paper fails under.
 *
 * One spelling, because the PDF reader and the picture reader reach the same honest answer the same way: a
 * page whose image is white paper and whose engine read nothing is a blank page, and an engine that read
 * nothing of a page that carries ink is not — which is why this is a code about the *image* rather than
 * about an empty reading.
 */
internal const val PAGE_BLANK_CODE: String = "PAGE_BLANK"

/**
 * The OCR tool cannot read this document at all, so the whole document fails.
 *
 * The distinction matters: a page OCR could not read is one failed unit among many and the rest of the
 * document still delivers, while a missing or unusable tool means every page would fail the same way.
 * Reporting the second as a page failure would fill a document with identical failures and bury the one
 * thing the operator has to act on, which is why this exception carries a code the pipeline reports
 * against the document instead.
 *
 * It lives beside the shared codes rather than in the one extractor that first needed it, because it is
 * not a PDF fact: the OCR seam throws it, every extractor that hands a page to a tool catches it, and it
 * is the type a whole-document refusal travels as. A copy per extractor would let the two disagree about
 * what the code means.
 */
class OcrUnavailableException(val code: String, message: String) : IOException(message)

/**
 * What a format can say about being read again from page images.
 *
 * A page-image reading needs pages, and not every format has them: a spreadsheet, a text file, a book
 * container and a word-processing document keep their existing extraction, and a rescan of one of them is
 * answered with the safe reason this carries rather than with rendering invented for it. Naming the reason
 * is what keeps "this cannot be rescanned" from being reported as a failure or as an empty document.
 */
sealed interface PageImageSupport {

    /** This format renders page images, so an engine can be asked to read them. */
    data object Supported : PageImageSupport

    /** This format has no pages to render; [code] is the safe reason a caller reports. */
    data class Unsupported(val code: String) : PageImageSupport {

        init {
            require(code.isNotBlank()) { "an unsupported page-image reason has to name itself" }
        }
    }
}

/**
 * The code a format that cannot be read from page images reports.
 *
 * One spelling on purpose: it reaches the queue, the CLI and the API the same way every other code does.
 */
const val PAGE_IMAGES_UNSUPPORTED_CODE: String = "PAGE_IMAGES_UNSUPPORTED"

/**
 * One citable unit an extractor produced, before the store gives it an identifier.
 *
 * Both text forms are carried: [extractedText] is what the tool produced, [searchText] is the form the
 * index will hold. [artifactRelativePath] and [artifactSha256] name a file the extractor wrote under the
 * document's artifact root — the OCR word boxes, a rendered page — so the unit and its artifact commit
 * together and a reader can later verify the artifact still matches the unit it belongs to.
 * [meanConfidence] is how sure an OCR tool was about this unit, and is absent for text that a parser read
 * rather than a tool recognised.
 *
 * [method] is which of the two ways this unit's text was read, and it is a required statement rather than
 * something derived: a parser's page and a recognised page are two methods, and `meanConfidence` is not
 * either of them (a page can be read by OCR and come back with no confidence to report, and a parser's text
 * is not evidence that OCR ran). Only the extractor knows which path it took, so only the extractor can say.
 *
 * [sourceImage] is the image an OCR reading was made from, and it is absent exactly when no image was read:
 * a text-layer page parsed out of a container has none, and so does a format that produced its text without
 * a raster. It is here rather than derived later because only this extractor saw which file the tool was
 * handed — the managed copy, or the bounded copy of a picture too large to hand over — and a durable page
 * that did not record it could not be told apart from one read from other pixels.
 *
 * [directText] is the *other* reading of one page, and it exists for exactly one mode: an attempt that reads
 * a page image even where a text layer already exists has two readings of that page — the text layer the
 * page carries and what the engine read from its pixels — and the first is what the second has to be
 * compared with and decided against. It is the same form the unit's own text is in (normalised, the form a
 * revision page holds), so the two can be compared as they stand. It is absent whenever there is no such
 * pair: a picture and a scanned page have no text layer, a layer that could not be read is not one, and
 * fill-missing either commits the layer as the unit or replaces it outright without ever handing two
 * readings of one page to a sink.
 */
data class ContentUnitDraft(
    val locator: SourceLocation,
    val extractedText: String,
    val searchText: String,
    val method: ExtractionMethod,
    val artifactRelativePath: String? = null,
    val artifactSha256: String? = null,
    val meanConfidence: Double? = null,
    val sourceImage: SourceImageProvenance? = null,
    val directText: String? = null,
)

/**
 * The extraction settings one import ran with.
 *
 * They are captured when the job is enqueued and travel in its payload, because a paused job has to be
 * resumable: a collection whose OCR languages change afterwards must not silently redefine which units are
 * already done.
 *
 * [ocrTool], [renderDpi], and [ebookTool] are filled when the job is created, from
 * `ToolProbe.extractionSettings` — the reading tool's reported version, the resolution pages are rendered
 * at, and the version of the converter that turns a Kindle or legacy container into an EPUB. They stay
 * absent only for settings a caller assembled by hand, and "absent" is itself part of the fingerprint: a
 * job whose tool nobody recorded must not compare equal to one whose tool was identified.
 *
 * [ebookTool] is here for the same reason as [ocrTool]: the converter produces the markup every Unit of a
 * converted book is read from, so a converter upgrade can change what a chapter says, and a fingerprint
 * that did not cover it would reuse the older version's sections as if they were the same evidence.
 */
@Serializable
data class ExtractionSettings(
    val ocrLanguages: String,
    val extractorSchemaVersion: String = EXTRACTOR_SCHEMA_VERSION,
    val ocrTool: String? = null,
    val renderDpi: Int? = null,
    val ebookTool: String? = null,
    /**
     * What an OCR attempt read this document with, or null when this extraction is not an OCR attempt.
     *
     * An import reads a document's own text layer with a parser, and a job written before OCR attempts
     * existed carries no attempt at all: both mean "nothing OCR-specific happened", which is exactly what
     * null says here. It is *not* a default engine — an attempt that named Tesseract at 300 DPI must not
     * reuse the checkpoints of a job that named nothing, which is why the absent identity is itself part of
     * the fingerprint. The identity carries only what can change the reading: the reviewer lives in the
     * settings snapshot and never reaches this field, so a reviewer edit cannot invalidate transcription.
     */
    val ocrAttempt: OcrAttemptIdentity? = null,
    /**
     * What an OCR attempt is for: filling missing text, or checking and improving what a page carries.
     *
     * The mode is a setting rather than a hint because it decides what the engine is *asked* to do, not
     * which engine is asked: fill-missing keeps a page's own usable text and reads only the pages that have
     * none, while check-and-improve reads every page image even where a text layer exists. It is part of the
     * fingerprint for the same reason — a page that was trusted in one mode was not read in the other, so
     * the two attempts' checkpoints are not each other's to reuse. A mode that reads images is an OCR
     * attempt by definition, which is why it cannot be set without one.
     */
    val ocrMode: OcrImportMode = OcrImportMode.FILL_MISSING,
) {

    init {
        require(ocrMode == OcrImportMode.FILL_MISSING || ocrAttempt != null) {
            "check-and-improve reads page images, so the settings have to name the attempt that reads them"
        }
    }

    /**
     * The engine this extraction reads page images with.
     *
     * The attempt's own engine when it names one, and Tesseract for an extraction that is not an OCR attempt
     * — an ordinary import reads a page that has no text of its own with the tool this build installs, which
     * is what it has always done. It is deliberately not a fallback: an attempt that names Surya is read by
     * Surya or refused, never by whichever engine happens to be configured.
     */
    fun readingEngine(): OcrEngine = ocrAttempt?.engine ?: OcrEngine.TESSERACT

    /**
     * These settings as one collection's OCR selection makes them.
     *
     * A collection that selects Tesseract and fill-missing is *unchanged*: nothing OCR-specific is added, so
     * an import of such a collection fingerprints exactly as it did before OCR engines existed and keeps
     * reusing the checkpoints it already committed. Anything else — another engine, or a mode that reads
     * images a page already has — is a different reading of the same bytes, so the attempt identity and the
     * mode travel into the fingerprint where they belong: a page committed under the legacy settings is not
     * evidence about a page read by Surya, or about a page whose text layer was checked rather than trusted.
     *
     * [snapshot] is the collection's settings as they were resolved into the attempt's snapshot, which is what
     * makes an import admitted before an edit keep reading with what it was admitted with.
     */
    fun forOcrSettings(snapshot: infoscry.ocr.OcrSettingsSnapshot): ExtractionSettings {
        val legacy = snapshot.engine == infoscry.ocr.OcrEngine.TESSERACT &&
            snapshot.mode == infoscry.ocr.OcrImportMode.FILL_MISSING &&
            snapshot.transcriptionProfileRevisionId == null
        if (legacy) return this
        return copy(ocrAttempt = snapshot.attemptIdentity(), ocrMode = snapshot.mode)
    }

    /**
     * These settings with the reading engine's runtime identity, discovered by [runtimeIdentityOf].
     *
     * It is asked only for settings that name an OCR attempt and record no runtime of their own: an
     * identity that admission recorded is what the attempt reads under and nothing here replaces it. An
     * attempt whose settings record no runtime — a payload written before admission probed, or an engine
     * that could not describe itself — has nothing to key its committed pages by, so the reader is asked
     * what it would read with now. What it answers is recorded whether or not one was expected, including
     * *no* identity, because an identity that could not be discovered must never compare equal to one that
     * was. An extraction that is not an OCR attempt is returned untouched: it has no attempt to describe,
     * and the tool it reads with is already part of the settings (`ocrTool`).
     */
    internal suspend fun withRuntimeIdentity(
        runtimeIdentityOf: suspend (OcrEngine) -> String?,
    ): ExtractionSettings {
        val attempt = ocrAttempt ?: return this
        return copy(ocrAttempt = attempt.copy(runtimeIdentity = runtimeIdentityOf(attempt.engine)))
    }

    /**
     * The settings snapshot one page of this extraction is read under, as the engine's seam takes it.
     *
     * [renderDpi] is the resolution the page was *actually* rendered at, which is the attempt's own fact and
     * not the one it asked for: a page brought within a pixel bound was read at the resolution that fitted.
     * The reviewer fields stay absent here — an extraction does not review, and a reviewer edit must not
     * change what reading a page produces.
     */
    fun pageOcrSettings(renderDpi: Int?): OcrSettingsSnapshot = OcrSettingsSnapshot(
        engine = readingEngine(),
        mode = ocrMode,
        language = ocrLanguages,
        extractorVersion = extractorSchemaVersion,
        transcriptionPromptVersion = ocrAttempt?.transcriptionPromptVersion ?: OCR_TRANSCRIPTION_PROMPT_VERSION,
        transcriptionProfileRevisionId = ocrAttempt?.profileRevisionId,
        toolVersion = ocrAttempt?.toolVersion ?: ocrTool,
        modelVersion = ocrAttempt?.modelVersion,
        renderDpi = renderDpi,
    )
}

/**
 * What a unit's checkpoints are keyed by: the same bytes, extracted the same way.
 *
 * The fingerprint is what makes a resumed or repeated import cheap without making it wrong. It covers
 * the document's own content hash and every setting that can change what the extractor produces, so
 * output committed under one fingerprint is reused only when it would be produced identically again. A
 * tool upgrade therefore does not quietly invalidate a paused job: reuse stops, and the operator decides
 * whether to restart extraction.
 */
@JvmInline
value class ExtractionFingerprint(val value: String) {

    override fun toString(): String = value

    companion object {

        /** The fingerprint of one document under one set of extraction settings. */
        fun of(sha256: String, settings: ExtractionSettings): ExtractionFingerprint {
            require(sha256.isNotBlank()) { "a fingerprint needs the document's content hash" }
            require(settings.ocrLanguages.isNotBlank()) {
                "a fingerprint needs the OCR languages the extraction ran with"
            }
            require(settings.extractorSchemaVersion.isNotBlank()) {
                "a fingerprint needs the extractor schema version"
            }
            // A canonical, ordered, line-delimited form: the same inputs always hash the same, and two
            // settings lists cannot collide by concatenating into the same string.
            val fields = buildList {
                add("sha256=$sha256")
                add("schema=${settings.extractorSchemaVersion}")
                add("ocr_languages=${settings.ocrLanguages}")
                add("ocr_tool=${legacyFingerprintField(settings.ocrTool)}")
                add("render_dpi=${settings.renderDpi ?: NONE}")
                add("ebook_tool=${legacyFingerprintField(settings.ebookTool)}")
                // The OCR block exists only when the settings name an attempt. Appending it — rather than
                // adding absent lines for a legacy instance — is what keeps a job or checkpoint written
                // before OCR attempts existed hashing byte-identically to the digest it was written with.
                settings.ocrAttempt?.let { attempt ->
                    add("ocr_engine=${attempt.engine}")
                    add("ocr_profile_revision=${fingerprintPresence(attempt.profileRevisionId)}")
                    add("ocr_attempt_language=${attempt.language}")
                    add("ocr_transcription_prompt=${attempt.transcriptionPromptVersion}")
                    add("ocr_attempt_tool=${fingerprintPresence(attempt.toolVersion)}")
                    add("ocr_model=${fingerprintPresence(attempt.modelVersion)}")
                    add("ocr_render_dpi=${attempt.renderDpi ?: NONE}")
                    add("ocr_extractor_schema=${attempt.extractorSchemaVersion}")
                    // What the machine said its runtime was *before* this attempt read anything. A commit
                    // under one runtime is not a reading another one produced, and an attempt that could not
                    // describe its runtime is a third thing: the absent form is a value of its own, so a
                    // checkpoint written under weights nobody named is not reused under named ones.
                    add("ocr_runtime=${fingerprintPresence(attempt.runtimeIdentity)}")
                    // What the attempt would have made of a picture it read: a build that decodes the same
                    // picture differently — as itself rather than as a reduced copy — read different pixels,
                    // and the document-level fingerprint is the only key an attempt's checkpoints answer to,
                    // so a policy this line does not cover would be one whose pages could be reused wrong.
                    add("ocr_picture_decoding_policy=${attempt.pictureDecodingPolicyVersion}")
                    // The mode belongs inside this block for the same reason the attempt does: an attempt
                    // that trusted a text layer committed a different page than one that read its image, so
                    // the two may not satisfy each other's checkpoints.
                    add("ocr_mode=${settings.ocrMode}")
                }
            }
            // One field per line is unambiguous only while no value carries a line of its own: an
            // `ocr_attempt_tool` of "x\nocr_model=y" with model "z" would compose exactly the lines of tool
            // "x" with model "y\nocr_model=z" — two different readings, one fingerprint. A value with a
            // line break in it is not a version, an id or a language this pipeline produces, so it is
            // refused here rather than encoded: escaping would leave the two readings comparable while
            // still calling them different.
            fields.forEach { field ->
                require(field.none { character -> character == '\n' || character == '\r' }) {
                    "an extraction fingerprint field may not contain a line break: " +
                        "'${field.substringBefore('=')}' does"
                }
            }
            val canonical = fields.joinToString("\n")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
            return ExtractionFingerprint(HexFormat.of().formatHex(digest))
        }

        private const val NONE = "none"

        /**
         * A legacy field whose absent form is already written into committed digests.
         *
         * Absence must keep composing exactly `none`, or every checkpoint an archive already holds stops
         * matching. What can be made safe is the other side: a probe accepts a tool's own first output line,
         * so a tool can truthfully report the literal word the absent form uses — and two different states
         * composing one digest is how a page refused because no tool ran gets reused by an attempt that has
         * one. A present value that would read as absence, or as the escape marker itself, is therefore
         * marked; every realistic value (a tool version, a converter version) is untouched, which is what
         * keeps the pinned digests byte-identical.
         */
        private fun legacyFingerprintField(value: String?): String = when {
            value == null -> NONE
            value == NONE || value.startsWith(ESCAPE_MARKER) -> ESCAPE_MARKER + value
            else -> value
        }

        /** The marker a value that would otherwise read as absence or as an escape carries. */
        private const val ESCAPE_MARKER = "escaped:"
    }
}

/**
 * What one extraction reports, one unit at a time.
 *
 * The stream is deliberately fine-grained: a document of ten thousand pages produces ten thousand
 * events and never a list of ten thousand units, because the attempt must be interruptible between any
 * two of them. A unit that fails is an event rather than an exception, so the units already delivered
 * stay delivered and the document keeps whatever was readable. [Finished] is only ever sent after the
 * extractor produced every unit it has: a terminal document failure must not send it, or the pipeline
 * would record an extraction as complete when it stopped early.
 */
sealed interface ExtractionEvent {

    /** One unit was produced and is ready to be committed. */
    data class UnitReady(val key: String, val ordinal: Int, val unit: ContentUnitDraft) : ExtractionEvent

    /** One unit could not be produced; the rest of the document is still attempted. */
    data class UnitFailed(val key: String, val ordinal: Int, val code: String) : ExtractionEvent

    /**
     * The extractor knows how many units its document has, before it has read them all.
     *
     * An extractor announces this as soon as it can (a PDF knows its page count when it opens the file, a
     * workbook its sheets), because until it does, progress can only be counted and cannot be compared to
     * anything. It is a fact the extractor states, never a prediction: an extractor with no total to
     * announce sends nothing, and its progress stays a count without a denominator.
     */
    data class Progress(val unitKind: UnitKind, val totalUnits: Int) : ExtractionEvent

    /** Every unit this extractor has was delivered. [metadata] is what it learned about the document. */
    data class Finished(val metadata: Map<String, String>, val totalUnits: Int) : ExtractionEvent
}

/**
 * The boundary of one unit of extraction, and the only place an extractor is allowed to work.
 *
 * Everything expensive or mutating happens inside [unit]: the extractor builds the unit, writes whatever
 * artifact belongs to it, and emits its event, and only then does this return. The collector holds the
 * shared mutation permit for exactly that span, which is what keeps a collection deletion or an index
 * rebuild from landing between an artifact and the checkpoint that describes it.
 *
 * The permit covers the collector's work as well, because a `flow` is collected inline: `emit` does not
 * return until the collector's body for that event has run, and the collector commits inside that body
 * without asking the gate again for work it is already admitted for. That in turn requires the flow to
 * be **unbuffered and without a background producer** — a `buffer`, a `channelFlow`, or a `flowOn`
 * would let the collector run after the permit was released, which is exactly the window this exists to
 * close.
 *
 * The unit is the granularity for a reason: the exclusive side has no timeout, so a permit held for a
 * whole document would stop every other writer in the process for as long as that document takes.
 *
 * What a permit guarantees is that **every event is emitted inside one** — not that every event gets one
 * of its own. An extractor that discovers halfway through a unit that the document cannot be read at all
 * emits the document-level refusal inside the permit it already holds.
 */
interface UnitBoundary {

    suspend fun <T> unit(block: suspend () -> T): T
}

/**
 * What one extractor run needs to know.
 *
 * [committedUnitKeys] is the resume point: keys an earlier attempt already committed under this same
 * fingerprint. The extractor must skip those before it does the expensive work — a cheap container parse
 * to find out which units exist is fine, rendering a page or running OCR for a committed key is not.
 * [artifactRoot] is a directory the extractor may write into, and it only ever writes inside [unit].
 * [artifactRoot] is where an artifact a unit names is resolved from, so a draft's
 * [ContentUnitDraft.artifactRelativePath] is relative to it.
 *
 * [originalFilename] is the name the file was published under, which is what a citation to a document whose
 * units have no numbering of their own — a picture — can honestly point at. It defaults to the managed
 * copy's own name so an extractor never has to guess, and callers that know the original pass it.
 */
data class ExtractionInput(
    val documentId: DocumentId,
    val managedPath: Path,
    val artifactRoot: Path,
    val settings: ExtractionSettings,
    val fingerprint: ExtractionFingerprint,
    val committedUnitKeys: Set<String>,
    val boundary: UnitBoundary,
    val originalFilename: String = managedPath.fileName.toString(),
    /**
     * The attempt's authority for sending this document's pages off this machine, or null when it has none.
     *
     * It travels with the input because a page image is read inside the reader that owns the engines, while
     * the allowance it leaves under belongs to the attempt: a job's twenty files share one allowance, and a
     * page may only leave after the scope that covers it was approved. An engine that dispatches externally
     * is built from this authority per attempt (see `PageOcrEngines.forAttempt`), and its absence is never
     * "unlimited": a destination off this machine with no authority is refused.
     */
    val dispatch: infoscry.ocr.OcrDispatchAuthority? = null,
) {

    /** Whether an earlier attempt already committed this unit under the same fingerprint. */
    fun isCommitted(key: String): Boolean = key in committedUnitKeys
}

/**
 * Reports a document-level refusal once, inside its own permit.
 *
 * A refusal already committed under this fingerprint is the answer to this attempt too: the next attempt
 * recognises it instead of deriving it again from the same bytes. Every extractor that can refuse a whole
 * document before it has a unit to name — an unreadable container, protected material, a missing tool, a
 * bound that the document is past — reports it this way, so the key is the same word in every extractor.
 *
 * This is for a refusal the extractor knows about before it starts producing units. An extractor that
 * discovers one *while* it holds a unit's permit — a tool that turns out to be unusable on the first page
 * it reads — uses [emitDocumentRefusal] instead of opening a second permit for one event.
 */
internal suspend fun FlowCollector<ExtractionEvent>.refuseDocument(
    input: ExtractionInput,
    key: String,
    code: String,
) {
    if (input.isCommitted(key)) return
    input.boundary.unit {
        emitDocumentRefusal(input, key, code)
    }
}

/**
 * Emits a document-level refusal into the permit the caller is already inside.
 *
 * The rule this and [refuseDocument] both keep is that every event is emitted inside a permit — not that
 * every event gets a permit of its own. An abort discovered in the middle of a unit belongs to the same
 * permit as that unit: opening a second one for the refusal would charge the gate twice for one step of
 * work without protecting anything extra.
 */
internal suspend fun FlowCollector<ExtractionEvent>.emitDocumentRefusal(
    input: ExtractionInput,
    key: String,
    code: String,
) {
    if (input.isCommitted(key)) return
    emit(ExtractionEvent.UnitFailed(key = key, ordinal = REFUSAL_ORDINAL, code = code))
}

/**
 * The ordinal a document-level refusal carries.
 *
 * It names no unit, so the ordinal only has to be the same one on every such row; the key is what says
 * the row is about the document rather than about one of its units.
 */
private const val REFUSAL_ORDINAL: Int = 0

/**
 * One format's way of turning a managed original into citable units.
 *
 * An extractor is selected by media type and produces a [Flow] of events, in ordinal order, sequentially:
 * the collector gates each unit, so a flow that produced units concurrently would defeat the gate. The
 * implementation owns its own cheap-parsing-versus-expensive-work split; what it must not do is decide
 * when a document is complete — that is what [ExtractionEvent.Finished] says, and only the extractor
 * knows whether it got there.
 */
interface DocumentExtractor {

    /** The media types this extractor claims, exactly as a detector reports them. */
    val supportedMediaTypes: Set<String>

    /**
     * Whether this format can be read again from page images, and why not when it cannot.
     *
     * A rescan has to render pages, and a format that has none says so here rather than having a rendering
     * invented for it: the answer is what an admission path reports before it asks anyone to pay for
     * reading a document whose pages do not exist. The default is the honest one for every format that is
     * not a paginated raster document.
     */
    val pageImageSupport: PageImageSupport get() = PageImageSupport.Unsupported(PAGE_IMAGES_UNSUPPORTED_CODE)

    /**
     * What the engine [kind] would read this format's pages with, discovered without reading a page.
     *
     * This is the one part of an attempt that only the reader can answer: the engines a page goes to belong
     * to the extractor that hands them the page images, not to the registry that chose the extractor. It is
     * asked before extraction, for the attempt that has no runtime identity of its own to key its committed
     * pages by: the answer then travels into the attempt's fingerprint, which is the key those pages are
     * looked up by.
     *
     * The default is the honest answer for every format that reads no page images: nothing to describe. An
     * extractor that does read them answers with the engine's own runtime identity, which is `null` when the
     * runtime cannot be described — an absence that never compares equal to a discovered identity, so a page
     * read under unknown weights is read again rather than reused under them.
     */
    suspend fun runtimeIdentity(kind: OcrEngine): String? = null

    fun extract(input: ExtractionInput): Flow<ExtractionEvent>
}

/**
 * Where the units of an extraction are committed, and what an attempt that resumes may skip.
 *
 * This is the durable half of the extraction contract. Both calls happen **inside** the unit boundary's
 * permit, so an implementation writes text, artifacts, and its checkpoint in one place and must not ask
 * the mutation gate again for the work it is already admitted for.
 *
 * The pipeline is honest about what it can promise: with [NONE], an extracted unit would live only as
 * long as the attempt, so the import does not run extractors at all rather than performing OCR whose
 * result is thrown away and letting a document look searchable when nothing was stored. Detection still
 * runs, because that is what decides whether an item is importable.
 */
interface ExtractionSink {

    /** Whether a unit committed here outlives the attempt. */
    val storesUnits: Boolean

    /** Keys a previous attempt committed for this document and fingerprint. */
    suspend fun committedKeys(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
    ): Set<String>

    /**
     * Keys an explicit retry may skip: what a previous attempt committed *successfully* under this
     * fingerprint, with its artifacts still intact.
     *
     * It defaults to [committedKeys] because that is the crash-resume answer, and a sink that cannot tell
     * a committed unit from a known failure has nothing better to offer. A durable sink overrides it,
     * since an explicit retry exists to revisit the units that failed.
     */
    suspend fun retryKeys(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
    ): Set<String> = committedKeys(documentId, fingerprint)

    /** Commits one delivered event. Returning means the unit is durable. */
    suspend fun deliver(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        event: ExtractionEvent,
    )

    /**
     * How many of this document's committed units still await a person's decision.
     *
     * A reading is not automatically done because every page was read: a page a model proposed and nobody
     * accepted is text the archive holds but has not decided about, and a document with one of those is not
     * complete however well its passages embedded. The answer belongs to the sink because the sink is what
     * committed the reading — an ordinary reading, a page's own text layer or a tool's output the archive
     * accepts, is finite here and owes nobody anything, which is why the default is none.
     */
    suspend fun awaitingDecision(documentId: DocumentId): Int = 0

    companion object {

        /**
         * The sink a pipeline uses when it has nowhere durable to put a unit.
         *
         * The extraction phase is then an explicit no-op: the document is copied and detected and stays in
         * `EXTRACTING`, because nothing about its text is durable and saying otherwise would claim it is
         * searchable. The application does not run this way — [StoredUnitsSink] is what an import commits
         * through — but a caller that has no store (a test of the no-store contract, a future pipeline that
         * reads without writing) gets a sink that refuses to pretend rather than one that silently drops
         * what it read.
         */
        val NONE: ExtractionSink = object : ExtractionSink {

            override val storesUnits: Boolean = false

            override suspend fun committedKeys(
                documentId: DocumentId,
                fingerprint: ExtractionFingerprint,
            ): Set<String> = emptySet()

            override suspend fun deliver(
                documentId: DocumentId,
                fingerprint: ExtractionFingerprint,
                event: ExtractionEvent,
            ): Unit = Unit
        }
    }
}
