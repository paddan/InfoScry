package infoscry.document

import infoscry.AppContext
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.ExtractionMethod
import infoscry.domain.SourceLocation
import infoscry.embedding.DocumentEmbedder
import infoscry.embedding.TestDocumentEmbedder
import infoscry.search.SearchFilters
import infoscry.search.SearchMode
import infoscry.storage.PageApproval
import infoscry.storage.RevisionChunkDraft
import infoscry.storage.RevisionPageDraft
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

/**
 * An embedder a restore test can count, switch off and make fail.
 *
 * A restore never reads a page again, so the only model call it may make is embedding the passages of the
 * reading it brings back. Counting those calls is how a test says "this restore embedded, and the embedder
 * that failed is the reason nothing changed", and switching [available] off is the machine whose accelerator
 * did not initialize: the restore must refuse rather than fall back to anything else.
 */
internal class SwitchableEmbedder : DocumentEmbedder {

    private val delegate = TestDocumentEmbedder()

    val calls = AtomicInteger()

    /** Whether the machine has an embedder at all; false is `null` from the provider, not a failure. */
    @Volatile
    var available: Boolean = true

    /** When set, the next embedding fails with it, as an accelerator that stopped answering would. */
    @Volatile
    var failure: RuntimeException? = null

    val provider: () -> DocumentEmbedder? = { if (available) this else null }

    override fun embedDocuments(texts: List<String>): List<FloatArray> {
        calls.incrementAndGet()
        failure?.let { throw it }
        return delegate.embedDocuments(texts)
    }
}

/** One document with three published readings of its second page, as the tests below leave it. */
internal data class RestoreDocument(
    val collectionId: CollectionId,
    val documentId: DocumentId,
    /** The two pages' stable identities, in ordinal order. They never change across any revision. */
    val unitIds: List<ContentUnitId>,
    /** The three published revisions, oldest first; the last is the active one. */
    val revisionIds: List<String>,
)

internal object RestoreFixtures {

    const val STABLE_PAGE = "harbour notes"
    val MARKET_READINGS = listOf("market first", "market second", "market third")

    private val EMBEDDER = TestDocumentEmbedder()
    private val SEQUENCE = AtomicInteger()

    /** Imports one text file as a document of [collectionId] and returns its id. */
    fun importDocument(context: AppContext, dataDir: Path, collectionId: CollectionId, text: String): DocumentId {
        val source = Files.createDirectories(dataDir.resolve("sources"))
            .resolve("restore-${SEQUENCE.incrementAndGet()}.txt")
        Files.writeString(source, text)
        return runBlocking { context.library.importFile(collectionId, source) }.document.id
    }

    /**
     * Publishes one two-page revision per entry of [marketReadings]: the first page never changes, the second
     * says the entry. Every revision after the first is staged as a replacement of the one before it.
     */
    fun publishGenerations(
        context: AppContext,
        dataDir: Path,
        collectionId: CollectionId,
        marketReadings: List<String> = MARKET_READINGS,
        documentId: DocumentId = importDocument(context, dataDir, collectionId, "source of ${SEQUENCE.get()}"),
    ): RestoreDocument {
        val unitIds = listOf(ContentUnitId.new(), ContentUnitId.new())
        val revisions = mutableListOf<String>()
        marketReadings.forEach { reading ->
            val candidate = stage(context, documentId, revisions.lastOrNull(), listOf(STABLE_PAGE, reading), unitIds)
            runBlocking { context.revisionPublication.publish(documentId, revisions.lastOrNull(), candidate) }
            revisions += candidate
        }
        return RestoreDocument(collectionId, documentId, unitIds, revisions)
    }

    /** One candidate revision with a page per entry of [pages], approved and fully embedded. */
    fun stage(
        context: AppContext,
        documentId: DocumentId,
        parentRevisionId: String?,
        pages: List<String>,
        unitIds: List<ContentUnitId>,
        approval: PageApproval = PageApproval.APPROVED,
    ): String {
        val revisionId = context.revisions.openCandidate(documentId, parentRevisionId, "TEST_REPLACEMENT")
        pages.forEachIndexed { ordinal, text ->
            context.revisions.appendPage(
                revisionId,
                RevisionPageDraft(
                    ordinal = ordinal,
                    unitId = unitIds[ordinal],
                    locator = SourceLocation.TextLines(ordinal + 1, ordinal + 1),
                    extractedText = text,
                    searchText = text,
                    extractionMethod = ExtractionMethod.OCR,
                    approval = approval,
                    chunks = listOf(
                        RevisionChunkDraft(
                            ordinal = 0,
                            text = text,
                            startOffset = 0,
                            endOffset = text.length,
                            tokenCount = 5,
                            tokenStart = 0,
                            tokenEnd = 4,
                            embedding = EMBEDDER.embedDocuments(listOf(text)).single(),
                        ),
                    ),
                ),
            )
        }
        return revisionId
    }

    /** What a keyword search for [query] over [collectionId] returns, as the hit texts. */
    fun searchTexts(context: AppContext, query: String, collectionId: CollectionId): List<String> =
        context.search.search(query, SearchMode.KEYWORD, SearchFilters(collectionId = collectionId))
            .hits.map { it.text }.sorted()

    /** The number of rows `table` holds for one document, by its document column. */
    fun rows(context: AppContext, table: String, documentId: DocumentId, column: String = "document_id"): Int =
        context.database.read { connection ->
            connection.prepareStatement("SELECT COUNT(*) FROM $table WHERE $column = ?").use { statement ->
                statement.setString(1, documentId.value)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    /** The number of revision-owned rows `table` holds across every revision of one document. */
    fun revisionRows(context: AppContext, table: String, documentId: DocumentId): Int =
        context.database.read { connection ->
            connection.prepareStatement(
                "SELECT COUNT(*) FROM $table WHERE revision_id IN " +
                    "(SELECT id FROM document_revisions WHERE document_id = ?)",
            ).use { statement ->
                statement.setString(1, documentId.value)
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }
}
