package infoscry.ask

import infoscry.config.AppPaths
import infoscry.domain.CollectionId
import infoscry.domain.Chunk
import infoscry.domain.ContentUnit
import infoscry.domain.ContentUnitId
import infoscry.ocr.PageStructure
import infoscry.search.SearchHit
import infoscry.storage.ContentStore

/**
 * The evidence text Ask and Investigate send for a unit whose page has a structure.
 *
 * It reads the structure through [ContentStore.structureOf], which answers null for any damaged or
 * mismatched artifact, so a unit without a usable structure keeps the text it had before. Every path that
 * cannot prove a rendering falls back to the text it would have sent without structure, and never throws:
 * a stored chunk whose offsets do not address its own text, or a hit that names another document, is sent
 * as it was, since rendering from such offsets could show other characters.
 */
class StructuredEvidenceSource(private val content: ContentStore, private val paths: AppPaths) {

    /**
     * The renderer for one call (one Ask request or one tool call). A unit read and its structure loaded once
     * serve every hit of that call, so a call re-reads nothing per hit.
     */
    fun session(collectionId: CollectionId): Session = Session(collectionId)

    inner class Session internal constructor(private val collectionId: CollectionId) {
        private val loaded = HashMap<ContentUnitId, Loaded>()

        /** The text [hit] carries as evidence: the rendered chunk span when its unit has a structure. */
        fun textOf(hit: SearchHit): String {
            val entry = entryOf(hit.unitId) ?: return hit.text
            if (entry.unit.documentId != hit.documentId) return hit.text
            val structure = entry.structure ?: return hit.text
            val chunk = entry.chunk(hit.chunkOrdinal) ?: return hit.text
            if (chunk.text != hit.text) return hit.text
            return runCatching { renderChunk(structure, entry.unit.searchText, chunk) }.getOrNull() ?: hit.text
        }

        /** The text a whole unit carries as evidence, for a unit read directly. */
        fun unitText(unit: ContentUnit): String {
            val structure = entryFor(unit).structure ?: return unit.extractedText
            return runCatching {
                StructuredEvidence.render(structure, unit.searchText, 0, unit.searchText.length)
            }.getOrNull() ?: unit.extractedText
        }

        private fun entryOf(unitId: ContentUnitId): Loaded? =
            loaded[unitId] ?: content.readUnit(unitId)?.let { entryFor(it) }

        private fun entryFor(unit: ContentUnit): Loaded = loaded.getOrPut(unit.id) { Loaded(unit) }

        /** One unit with its structure and its chunks, each read at most once per call. */
        private inner class Loaded(val unit: ContentUnit) {
            val structure: PageStructure? by lazy {
                content.structureOf(unit, paths.artifactsDir(collectionId, unit.documentId))
            }
            private val chunksByOrdinal: Map<Int, Chunk> by lazy {
                content.chunksOf(unit.id).associateBy { it.ordinal }
            }

            fun chunk(ordinal: Int): Chunk? = chunksByOrdinal[ordinal]
        }
    }

    private companion object {
        /**
         * The Markdown for [chunk]'s span, once its offsets are shown to address its own text. Offsets that
         * fall outside the unit, or a span whose characters are not the chunk's body, throw here and are
         * caught by the caller.
         */
        fun renderChunk(structure: PageStructure, searchText: String, chunk: Chunk): String? {
            val start = chunk.startOffset
            val end = chunk.endOffset
            require(start >= 0 && end > start && end <= searchText.length) { "a chunk's span lies inside its unit" }
            require(chunk.text.endsWith(searchText.substring(start, end))) { "a chunk's text is its span" }
            return StructuredEvidence.render(structure, searchText, start, end)
        }
    }
}
