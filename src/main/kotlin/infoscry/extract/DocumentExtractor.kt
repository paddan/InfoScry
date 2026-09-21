package infoscry.extract

import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
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
 * One citable unit an extractor produced, before the store gives it an identifier.
 *
 * Both text forms are carried: [extractedText] is what the tool produced, [searchText] is the form the
 * index will hold. [artifactRelativePath] and [artifactSha256] name a file the extractor wrote under the
 * document's artifact root — the OCR word boxes, a rendered page — so the unit and its artifact commit
 * together and a reader can later verify the artifact still matches the unit it belongs to.
 * [meanConfidence] is how sure an OCR tool was about this unit, and is absent for text that a parser read
 * rather than a tool recognised.
 */
data class ContentUnitDraft(
    val locator: SourceLocation,
    val extractedText: String,
    val searchText: String,
    val artifactRelativePath: String? = null,
    val artifactSha256: String? = null,
    val meanConfidence: Double? = null,
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
)

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
            val canonical = listOf(
                "sha256=$sha256",
                "schema=${settings.extractorSchemaVersion}",
                "ocr_languages=${settings.ocrLanguages}",
                "ocr_tool=${settings.ocrTool ?: NONE}",
                "render_dpi=${settings.renderDpi ?: NONE}",
                "ebook_tool=${settings.ebookTool ?: NONE}",
            ).joinToString("\n")
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
            return ExtractionFingerprint(HexFormat.of().formatHex(digest))
        }

        private const val NONE = "none"
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

    /** Commits one delivered event. Returning means the unit is durable. */
    suspend fun deliver(
        documentId: DocumentId,
        fingerprint: ExtractionFingerprint,
        event: ExtractionEvent,
    )

    companion object {

        /**
         * Used until the durable unit store exists. The extraction phase is an explicit no-op: the
         * document is copied and detected and stays in `EXTRACTING`, because nothing about its text is
         * durable and saying otherwise would claim it is searchable.
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
