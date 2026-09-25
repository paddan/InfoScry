package infoscry.investigate

import infoscry.ask.ContextPacker
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnit
import infoscry.domain.ContentUnitId
import infoscry.domain.Document
import infoscry.domain.DocumentId
import infoscry.domain.DocumentStatus
import infoscry.domain.SourceLocation
import infoscry.llm.ToolDefinition
import infoscry.search.SearchFilters
import infoscry.search.SearchMode
import infoscry.search.SearchOutcome
import infoscry.search.SearchService
import infoscry.search.SearchUnavailableException
import infoscry.storage.ContentStore
import infoscry.storage.ContentUnitSummary
import infoscry.storage.DocumentStore
import java.nio.charset.StandardCharsets
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Bounded evidence read from one collection. IDs are opaque; no path ever appears. */
@Serializable
data class ToolEvidence(
    val evidenceId: String,
    val sourceUnitId: String,
    val locator: SourceLocation,
    val locatorLabel: String,
    val text: String,
    val truncated: Boolean,
)

/** The outcome of one tool call: either the exact JSON [payloadJson] the model receives, or a typed failure. */
sealed interface ToolResult {
    data class Success(val payloadJson: String, val evidence: List<ToolEvidence>) : ToolResult
    data class Failure(val code: String, val message: String) : ToolResult
}

/** The retrieval boundary the tools read through; kept separable so the tools are testable without an index. */
fun interface InvestigationSearch {
    fun search(queryText: String, mode: SearchMode, filters: SearchFilters): SearchOutcome
}

/**
 * The five bounded Investigate tools, all fixed to one [collectionId].
 *
 * The model may only pass opaque ids and safe filters: every tool re-checks the stored collection of the
 * document or unit it names, `search_collection` forces its own collection scope, and no schema or payload
 * ever carries a path. Each encoded result — payload JSON plus evidence text, both measured as UTF-8 bytes —
 * is capped at [maxResultBytes]; excerpts are shortened at line or word boundaries before anything is
 * dropped, and a result whose bare metadata cannot fit fails with `CONTEXT_BUDGET_EXCEEDED` instead of
 * emitting broken JSON.
 */
class InvestigationTools(
    private val collectionId: CollectionId,
    private val search: InvestigationSearch,
    private val content: ContentStore,
    private val documents: DocumentStore,
    private val maxResultBytes: Int = DEFAULT_MAX_RESULT_BYTES,
) {
    constructor(
        collectionId: CollectionId,
        search: SearchService,
        content: ContentStore,
        documents: DocumentStore,
        maxResultBytes: Int = DEFAULT_MAX_RESULT_BYTES,
    ) : this(collectionId, InvestigationSearch(search::search), content, documents, maxResultBytes)

    init {
        require(maxResultBytes > 0) { "maxResultBytes must be positive, was $maxResultBytes" }
    }

    /** The five tool definitions exactly as the model sees them; no path or scope parameter among them. */
    val definitions: List<ToolDefinition> = listOf(
        ToolDefinition(TOOL_SEARCH, SEARCH_DESCRIPTION, SEARCH_SCHEMA),
        ToolDefinition(TOOL_DOCUMENT_METADATA, DOCUMENT_METADATA_DESCRIPTION, DOCUMENT_ID_SCHEMA),
        ToolDefinition(TOOL_DOCUMENT_STRUCTURE, DOCUMENT_STRUCTURE_DESCRIPTION, DOCUMENT_ID_SCHEMA),
        ToolDefinition(TOOL_READ_UNIT, READ_UNIT_DESCRIPTION, UNIT_ID_SCHEMA),
        ToolDefinition(TOOL_READ_ADJACENT, READ_ADJACENT_DESCRIPTION, ADJACENT_SCHEMA),
    )

    fun execute(name: String, argumentsJson: String): ToolResult {
        var nextId = 0
        return execute(name, argumentsJson) { "S${++nextId}" }
    }

    fun execute(name: String, argumentsJson: String, nextEvidenceId: () -> String): ToolResult = when (name) {
        TOOL_SEARCH -> searchCollection(argumentsJson, nextEvidenceId)
        TOOL_DOCUMENT_METADATA -> documentMetadata(argumentsJson)
        TOOL_DOCUMENT_STRUCTURE -> documentStructure(argumentsJson)
        TOOL_READ_UNIT -> readUnit(argumentsJson, nextEvidenceId)
        TOOL_READ_ADJACENT -> readAdjacent(argumentsJson, nextEvidenceId)
        else -> ToolResult.Failure(CODE_UNKNOWN_TOOL, "no tool named '$name'")
    }

    private fun searchCollection(argumentsJson: String, nextEvidenceId: () -> String): ToolResult = parse<SearchArgs>(argumentsJson) { args ->
        val outcome = try {
            search.search(args.query, modeOf(args.searchMode), filtersOf(args.filters))
        } catch (unavailable: SearchUnavailableException) {
            return ToolResult.Failure(CODE_SEARCH_UNAVAILABLE, "${unavailable.code}: ${unavailable.remedy}")
        }
        val limit = (args.limit ?: DEFAULT_SEARCH_LIMIT).coerceIn(1, MAX_SEARCH_LIMIT)
        val items = outcome.hits.asSequence()
            .filter { it.collectionId == collectionId } // never trust a hit to name our scope
            .take(limit)
            .map { hit ->
                EvidenceItem(hit.unitId.value, hit.locator, hit.locatorLabel, escape(hit.text))
            }
            .toMutableList()
        bounded(items, { size -> size - 1 }, nextEvidenceId) // drop the lowest-ranked hit only as a last resort
    }

    private fun documentMetadata(argumentsJson: String): ToolResult = parse<DocumentIdArg>(argumentsJson) { args ->
        val document = scopedDocument(DocumentId(args.documentId))
            ?: return ToolResult.Failure(CODE_NOT_FOUND, "the requested document is not readable here")
        val payload = JSON.encodeToString(
            DocumentMetadataPayload(
                documentId = document.id.value,
                mediaType = document.mediaType,
                originalFilename = document.originalFilename,
                sizeBytes = document.sizeBytes,
                status = document.status.name,
                title = document.title,
                author = document.author,
                language = document.language,
                createdAt = document.createdAt,
                updatedAt = document.updatedAt,
                errorCode = document.errorCode,
                errorMessage = document.errorMessage,
            ),
        )
        bound(payload, emptyList())
    }

    private fun documentStructure(argumentsJson: String): ToolResult = parse<DocumentIdArg>(argumentsJson) { args ->
        val document = scopedDocument(DocumentId(args.documentId))
            ?: return ToolResult.Failure(CODE_NOT_FOUND, "the requested document is not readable here")
        val entries = content.listStructure(document.id).map { StructurePayload(it.id.value, it.ordinal, it.locator) }
        // Keep the leading entries: the listing is capped like every other result, and the ordinals
        // themselves tell the model how much of the document it is seeing.
        var low = 0
        var high = entries.size
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (JSON.encodeToString(entries.take(mid)).utf8Bytes() <= maxResultBytes) low = mid else high = mid - 1
        }
        if (low == 0) {
            return ToolResult.Failure(
                CODE_BUDGET_EXCEEDED,
                "this result exceeds the ${maxResultBytes}-byte tool cap even without excerpt text",
            )
        }
        val payload = JSON.encodeToString(entries.take(low))
        ToolResult.Success(payload, emptyList())
    }

    private fun readUnit(argumentsJson: String, nextEvidenceId: () -> String): ToolResult = parse<UnitIdArg>(argumentsJson) { args ->
        val unit = scopedUnit(ContentUnitId(args.contentUnitId))
            ?: return ToolResult.Failure(CODE_NOT_FOUND, "the requested content unit is not readable here")
        bounded(mutableListOf(itemOf(unit)), { 0 }, nextEvidenceId)
    }

    private fun readAdjacent(argumentsJson: String, nextEvidenceId: () -> String): ToolResult = parse<AdjacentArgs>(argumentsJson) { args ->
        val unit = scopedUnit(ContentUnitId(args.contentUnitId))
            ?: return ToolResult.Failure(CODE_NOT_FOUND, "the requested content unit is not readable here")
        val before = (args.before ?: 0).coerceIn(0, MAX_ADJACENT)
        val after = (args.after ?: 0).coerceIn(0, MAX_ADJACENT)
        val beforeUnits = if (before == 0) emptyList() else content
            .listUnits(unit.documentId, afterOrdinal = maxOf(-1, unit.ordinal - before - 1), limit = before)
            .filter { it.ordinal < unit.ordinal } // the requested unit itself is not a neighbour
        val afterUnits = if (after == 0) emptyList() else content.listUnits(unit.documentId, afterOrdinal = unit.ordinal, limit = after)

        val items = ArrayList<EvidenceItem>(before + 1 + after)
        beforeUnits.forEach { items += itemOf(it) }
        items += itemOf(unit)
        afterUnits.forEach { items += itemOf(it) }
        val sides = FarthestNeighbour(beforeRemaining = items.size - 1 - afterUnits.size)
        bounded(items, { size -> sides.dropIndex(size) }, nextEvidenceId)
    }

    private fun scopedDocument(id: DocumentId): Document? =
        documents.get(id)?.takeIf { it.collectionId == collectionId }

    private fun scopedUnit(id: ContentUnitId): ContentUnit? {
        val unit = content.readUnit(id) ?: return null
        val document = documents.get(unit.documentId) ?: return null
        return if (document.collectionId == collectionId) unit else null
    }

    private fun itemOf(unit: ContentUnit): EvidenceItem =
        EvidenceItem(unit.id.value, unit.locator, unit.locator.describe(), escape(unit.extractedText))

    private fun filtersOf(arg: SearchFiltersArg?): SearchFilters = SearchFilters(
        collectionId = collectionId,
        mediaTypes = arg?.mediaTypes.orEmpty(),
        titleAuthorOrLanguageContains = arg?.titleAuthorOrLanguageContains,
        importedFrom = arg?.importedFrom,
        importedUntil = arg?.importedUntil,
        statuses = arg?.statuses.orEmpty().mapTo(HashSet()) { DocumentStatus.valueOf(it) },
        ocrOnly = arg?.ocrOnly ?: false,
    )

    private fun modeOf(argument: String?): SearchMode = when (argument?.uppercase(Locale.ROOT)) {
        null -> SearchMode.HYBRID
        "KEYWORD" -> SearchMode.KEYWORD
        "SEMANTIC" -> SearchMode.SEMANTIC
        "HYBRID" -> SearchMode.HYBRID
        else -> throw IllegalArgumentException("unknown search mode '$argument'")
    }

    /** Parses and validates one tool call; anything a model can pass is untrusted. */
    private inline fun <reified T : Any> parse(argumentsJson: String, block: (T) -> ToolResult): ToolResult = try {
        block(JSON.decodeFromString<T>(argumentsJson))
    } catch (_: SerializationException) {
        ToolResult.Failure(CODE_INVALID_ARGUMENTS, "the tool arguments are not valid JSON for this tool")
    } catch (_: IllegalArgumentException) {
        ToolResult.Failure(CODE_INVALID_ARGUMENTS, "the tool arguments are not valid JSON for this tool")
    }

    /**
     * Fits [items] under [maxResultBytes]: the payload JSON plus each evidence text, all as UTF-8 bytes.
     * Texts are shortened at line or word boundaries first, whole results are dropped only when no text
     * can shrink, and a single result that cannot fit even without its text fails the call.
     */
    private fun bounded(items: MutableList<EvidenceItem>, dropIndex: (Int) -> Int, nextEvidenceId: () -> String): ToolResult {
        items.forEach { it.evidenceId = nextEvidenceId() }
        while (true) {
            if (totalBytes(items) <= maxResultBytes) {
                return ToolResult.Success(JSON.encodeToString(items.map { it.entry }), items.map { it.entry })
            }
            if (shortenToFit(items)) continue // a shrink that fits resolves the result on the next pass
            if (items.size > 1) {
                items.removeAt(dropIndex(items.size))
            } else {
                return ToolResult.Failure(
                    CODE_BUDGET_EXCEEDED,
                    "this result exceeds the ${maxResultBytes}-byte tool cap even without excerpt text",
                )
            }
        }
    }

    /** The exact cap measure: the encoded payload JSON plus every evidence text, in UTF-8 bytes. */
    private fun totalBytes(items: List<EvidenceItem>): Int =
        JSON.encodeToString(items.map { it.entry }).utf8Bytes() + items.sumOf { it.text.utf8Bytes() }

    /**
     * Shortens the largest text to the longest line- or word-boundary prefix bringing the whole result
     * under the cap. The payload embeds each text, so a leftover-bytes estimate goes negative on a large
     * unit; instead the budget is searched against the real encoded total, which is monotone in the
     * prefix. False when even an empty largest text cannot fit.
     */
    private fun shortenToFit(items: MutableList<EvidenceItem>): Boolean {
        val largest = items.maxByOrNull { it.text.utf8Bytes() } ?: return false
        val original = largest.text
        if (original.isEmpty()) return false
        fun fits(budget: Int): Boolean {
            largest.text = prefixWithinUtf8(original, budget)
            return totalBytes(items) <= maxResultBytes
        }
        if (!fits(0)) {
            largest.text = original
            return false
        }
        var low = 0
        var high = original.utf8Bytes()
        while (low < high) {
            val mid = (low + high + 1) / 2
            if (fits(mid)) low = mid else high = mid - 1
        }
        fits(low)
        if (largest.text != original) largest.truncated = true
        return true
    }

    /** The longest prefix fitting [maxBytes], refined to a line or word boundary when one lies inside it. */
    private fun prefixWithinUtf8(text: String, maxBytes: Int): String {
        var bytes = 0
        var end = 0
        while (end < text.length) {
            val nextBytes = bytes + text.substring(end, end + 1).toByteArray(StandardCharsets.UTF_8).size
            if (nextBytes > maxBytes) break
            bytes = nextBytes
            end += 1
        }
        if (end == text.length) return text
        val boundary = maxOf(text.lastIndexOf('\n', end - 1), text.lastIndexOf(' ', end - 1))
        if (boundary > 0) return text.substring(0, boundary)
        return text.substring(0, end)
    }

    private fun bound(payload: String, evidence: List<ToolEvidence>): ToolResult {
        val size = payload.utf8Bytes() + evidence.sumOf { it.text.utf8Bytes() }
        return if (size <= maxResultBytes) {
            ToolResult.Success(payload, evidence)
        } else {
            ToolResult.Failure(
                CODE_BUDGET_EXCEEDED,
                "this result exceeds the ${maxResultBytes}-byte tool cap even without excerpt text",
            )
        }
    }

    /** Delivers text with the evidence-frame delimiters neutralized exactly as the Ask packer does. */
    private fun escape(text: String): String = ContextPacker.escapeEvidence(text)

    private fun String.utf8Bytes(): Int = toByteArray(StandardCharsets.UTF_8).size

    /** One bounded evidence row being reduced; a whole row is a unit of truncation, never half of one. */
    private class EvidenceItem(
        val sourceUnitId: String,
        val locator: SourceLocation,
        val locatorLabel: String,
        var text: String,
        var truncated: Boolean = false,
        var evidenceId: String = "",
    ) {
        val entry: ToolEvidence get() = ToolEvidence(evidenceId, sourceUnitId, locator, locatorLabel, text, truncated)
    }

    /** Drops the farther of the two outer neighbours first, so the requested unit keeps its context. */
    private class FarthestNeighbour(private var beforeRemaining: Int) {
        fun dropIndex(size: Int): Int {
            val afterRemaining = size - 1 - beforeRemaining
            return if (beforeRemaining > afterRemaining) {
                beforeRemaining -= 1
                0
            } else {
                size - 1
            }
        }
    }

    @Serializable
    private data class SearchArgs(
        val query: String,
        val searchMode: String? = null,
        val filters: SearchFiltersArg? = null,
        val limit: Int? = null,
    )

    /** Only the collection-safe criteria; a caller can neither name a scope nor probe paths. */
    @Serializable
    private data class SearchFiltersArg(
        val mediaTypes: Set<String>? = null,
        val titleAuthorOrLanguageContains: String? = null,
        val importedFrom: String? = null,
        val importedUntil: String? = null,
        val statuses: Set<String>? = null,
        val ocrOnly: Boolean? = null,
    )

    @Serializable
    private data class DocumentIdArg(val documentId: String)

    @Serializable
    private data class UnitIdArg(val contentUnitId: String)

    @Serializable
    private data class AdjacentArgs(
        val contentUnitId: String,
        val before: Int? = null,
        val after: Int? = null,
    )

    @Serializable
    private data class DocumentMetadataPayload(
        val documentId: String,
        val mediaType: String,
        val originalFilename: String,
        val sizeBytes: Long,
        val status: String,
        val title: String?,
        val author: String?,
        val language: String?,
        val createdAt: String,
        val updatedAt: String,
        val errorCode: String?,
        val errorMessage: String?,
    )

    @Serializable
    private data class StructurePayload(
        val contentUnitId: String,
        val ordinal: Int,
        val locator: SourceLocation,
    )

    private companion object {
        const val DEFAULT_MAX_RESULT_BYTES = 2_048
        const val MAX_SEARCH_LIMIT = 10
        const val DEFAULT_SEARCH_LIMIT = 10
        const val MAX_ADJACENT = 5

        const val TOOL_SEARCH = "search_collection"
        const val TOOL_DOCUMENT_METADATA = "get_document_metadata"
        const val TOOL_DOCUMENT_STRUCTURE = "list_document_structure"
        const val TOOL_READ_UNIT = "read_content_unit"
        const val TOOL_READ_ADJACENT = "read_adjacent_units"

        const val CODE_UNKNOWN_TOOL = "UNKNOWN_TOOL"
        const val CODE_INVALID_ARGUMENTS = "INVALID_ARGUMENTS"
        const val CODE_NOT_FOUND = "NOT_FOUND"
        const val CODE_BUDGET_EXCEEDED = "CONTEXT_BUDGET_EXCEEDED"
        const val CODE_SEARCH_UNAVAILABLE = "SEARCH_UNAVAILABLE"

        const val SEARCH_DESCRIPTION = "Search this conversation's documents by keyword, semantic, or hybrid mode; returns the top matching reading units with their source locations."
        const val DOCUMENT_METADATA_DESCRIPTION = "Return metadata about one document: media type, size, status, title, author, language, and timestamps."
        const val DOCUMENT_STRUCTURE_DESCRIPTION = "List a document's reading units in order with their source locations."
        const val READ_UNIT_DESCRIPTION = "Read one content unit's extracted text, bounded to the allowed excerpt size."
        const val READ_ADJACENT_DESCRIPTION = "Read neighboring content units around one unit, within the same document, bounded by the excerpt cap."

        const val DOCUMENT_ID_SCHEMA = """{"type":"object","properties":{"documentId":{"type":"string"}},"required":["documentId"]}"""
        const val UNIT_ID_SCHEMA = """{"type":"object","properties":{"contentUnitId":{"type":"string"}},"required":["contentUnitId"]}"""
        const val ADJACENT_SCHEMA = """{"type":"object","properties":{"contentUnitId":{"type":"string"},"before":{"type":"integer"},"after":{"type":"integer"}},"required":["contentUnitId"]}"""
        const val SEARCH_SCHEMA = """{"type":"object","properties":{"query":{"type":"string"},"searchMode":{"type":"string","enum":["KEYWORD","SEMANTIC","HYBRID"]},"filters":{"type":"object","properties":{"mediaTypes":{"type":"array","items":{"type":"string"}},"titleAuthorOrLanguageContains":{"type":"string"},"importedFrom":{"type":"string"},"importedUntil":{"type":"string"},"statuses":{"type":"array","items":{"type":"string"}},"ocrOnly":{"type":"boolean"}}},"limit":{"type":"integer"}},"required":["query"]}"""

        val JSON: Json = Json
    }
}
