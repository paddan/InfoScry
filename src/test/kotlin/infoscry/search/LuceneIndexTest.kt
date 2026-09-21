package infoscry.search

import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The search index: an idempotent Lucene store of one Lucene document per searchable chunk, addressed
 * through an atomic generation marker that Task 18's rebuild builds on.
 */
class LuceneIndexTest {

    private lateinit var indexDir: Path

    @BeforeTest
    fun createTemporaryIndexDirectory() {
        indexDir = Files.createTempDirectory("infoscry-lucene")
    }

    @AfterTest
    fun removeTemporaryIndexDirectory() {
        indexDir.toFile().deleteRecursively()
    }

    @Test
    fun `a fresh index creates one generation and resolves it on reopen`() {
        val first = LuceneIndex.open(indexDir, IDENTITY)
        val name = first.name
        assertEquals(LuceneSchema.SCHEMA_VERSION, first.schemaStatus.assertReady().schemaVersion)
        first.close()

        // The marker names the active generation, and every open resolves it rather than guessing.
        assertEquals(name, markerNames(indexDir).single())
        assertTrue(Files.isDirectory(indexDir.resolve(name)), "the generation the marker names must exist")

        val reopened = LuceneIndex.open(indexDir, IDENTITY)
        try {
            assertEquals(name, reopened.name)
        } finally {
            reopened.close()
        }
    }

    @Test
    fun `an incomplete generation is never selected`() {
        LuceneIndex.open(indexDir, IDENTITY).close()

        // A marker that names a directory that does not exist must fail loudly instead of silently
        // creating or selecting a different generation.
        Files.writeString(indexDir.resolve("current"), "lucene-gone")

        assertFailsWith<IllegalStateException> { LuceneIndex.open(indexDir, IDENTITY) }
    }

    @Test
    fun `a fresh index with leftover generations but no marker is a recovery error, not a guess`() {
        Files.createDirectories(indexDir.resolve("lucene-stray"))
        assertFailsWith<IllegalStateException> { LuceneIndex.open(indexDir, IDENTITY) }
    }

    @Test
    fun `replacing a document twice leaves one set of chunks`() {
        LuceneIndex.open(indexDir, IDENTITY).use { index ->
            val rows = chunksFor(DOCUMENT, listOf("first chunk", "second chunk", "third chunk"))
            runBlocking { index.replaceDocument(rows) }

            val again = chunksFor(DOCUMENT, listOf("first chunk", "second chunk", "third chunk"))
            runBlocking { index.replaceDocument(again) }

            assertEquals(3, index.chunkCount(COLLECTION, DOCUMENT), "a replacement must not duplicate chunks")
        }
    }

    @Test
    fun `deleting a document removes all of its chunks`() {
        LuceneIndex.open(indexDir, IDENTITY).use { index ->
            runBlocking { index.replaceDocument(chunksFor(DOCUMENT, listOf("only", "two", "chunks"))) }
            assertEquals(3, index.chunkCount(COLLECTION, DOCUMENT))

            runBlocking { index.deleteDocument(DOCUMENT) }

            assertEquals(0, index.chunkCount(COLLECTION, DOCUMENT))
        }
    }

    @Test
    fun `keyword search returns the stored identity, snippet and label filtered by collection`() {
        LuceneIndex.open(indexDir, IDENTITY).use { index ->
            runBlocking {
                // No stemming: the second document must contain the token "budget" itself to match.
                index.replaceDocument(chunksFor(DOCUMENT, listOf("the nightfall report", "budget figures")))
                index.replaceDocument(
                    chunksFor(OTHER_DOCUMENT, listOf("a different report about the budget"), collection = OTHER_COLLECTION),
                )
            }

            val hits = index.searchKeyword(COLLECTION, "budget", limit = 10)

            assertEquals(1, hits.size)
            with(hits.single()) {
                assertEquals(DOCUMENT.value, documentId)
                assertTrue(text.contains("budget"))
                assertEquals(LOCATOR_LABEL, locatorLabel)
            }

            // The same query across every collection sees both documents.
            val all = index.searchKeyword(null, "budget", limit = 10)
            assertEquals(2, all.size)
        }
    }

    @Test
    fun `vector search returns the closest vectors and filters by collection`() {
        LuceneIndex.open(indexDir, IDENTITY).use { index ->
            val target = vectorFor("nightfall")
            runBlocking {
                index.replaceDocument(chunksFor(DOCUMENT, listOf("nightfall operations")))
                index.replaceDocument(
                    chunksFor(OTHER_DOCUMENT, listOf("unrelated summary"), collection = OTHER_COLLECTION),
                )
            }

            val hits = index.searchVector(COLLECTION, target, limit = 5)

            assertEquals(1, hits.size)
            assertEquals(DOCUMENT.value, hits.single().documentId)

            val all = index.searchVector(null, target, limit = 5)
            assertTrue(all.size >= 2, "without a collection filter every collection is eligible")
        }
    }

    @Test
    fun `deleting a collection removes every chunk that belongs to it`() {
        LuceneIndex.open(indexDir, IDENTITY).use { index ->
            runBlocking {
                index.replaceDocument(chunksFor(DOCUMENT, listOf("nightfall", "report")))
                index.replaceDocument(chunksFor(OTHER_DOCUMENT, listOf("other", "report"), collection = OTHER_COLLECTION))
            }

            runBlocking { index.deleteCollection(COLLECTION) }

            assertEquals(0, index.chunkCount(COLLECTION, DOCUMENT))
            assertEquals(2, index.chunkCount(OTHER_COLLECTION, OTHER_DOCUMENT))
        }
    }

    @Test
    fun `an index built by a different model identity reports rebuild required`() {
        LuceneIndex.open(indexDir, IDENTITY).use { index ->
            runBlocking { index.replaceDocument(chunksFor(DOCUMENT, listOf("anything"))) }
        }

        val other = LuceneIndex.open(indexDir, IDENTITY.copy(modelFingerprint = "some-other-model"))
        try {
            assertEquals(
                LuceneSchema.SCHEMA_VERSION,
                other.schemaStatus.assertRebuildRequired().schemaVersion,
            )
            assertTrue(other.schemaStatus.reasons().any { it.contains("model") })
        } finally {
            other.close()
        }
    }

    private fun chunksFor(
        documentId: DocumentId,
        texts: List<String>,
        collection: CollectionId = COLLECTION,
    ): List<DocumentRow> = texts.mapIndexed { ordinal, text ->
        DocumentRow(
            collectionId = collection,
            documentId = documentId,
            unitId = ContentUnitId.new(),
            chunk = Chunk(
                id = ChunkId.new(),
                contentUnitId = ContentUnitId.new(),
                ordinal = ordinal,
                text = text,
                startOffset = 0,
                endOffset = text.length,
                tokenCount = text.length,
                tokenStart = 0,
                // Chunk prohibits an end offset at or beyond the token count: the token indices are `0 until tokenCount`.
                tokenEnd = (text.length - 1).coerceAtLeast(0),
            ),
            locator = SourceLocation.TextLines(1, 1),
            locatorLabel = LOCATOR_LABEL,
            vector = vectorFor(text),
        )
    }

    private fun markerNames(indexDir: Path): List<String> =
        Files.readAllLines(indexDir.resolve("current")).filter { it.isNotBlank() }

    private companion object {
        val IDENTITY = IndexIdentity(
            schemaVersion = LuceneSchema.SCHEMA_VERSION,
            model = "intfloat/multilingual-e5-base",
            modelRevision = "d128750597153bb5987e10b1c3493a34e5a4502a",
            modelFingerprint = "expected-model-fingerprint",
            dimension = 768,
        )

        val COLLECTION = CollectionId("nightfall")
        val OTHER_COLLECTION = CollectionId("acme")
        val DOCUMENT = DocumentId.new()
        val OTHER_DOCUMENT = DocumentId.new()
        const val LOCATOR_LABEL = "page 14"
    }
}

/** A deterministic 768-dimension unit vector, so a test can compare nearest neighbours meaningfully. */
internal fun vectorFor(text: String): FloatArray {
    val seed = text.hashCode()
    val vector = FloatArray(768) { index ->
        val value = java.util.Random(seed.toLong() + index * 31L).nextFloat()
        if (index % 2 == 0) value else -value
    }
    // Normalize to unit length so DOT_PRODUCT behaves like cosine similarity.
    val norm = kotlin.math.sqrt(vector.sumOf { (it.toDouble()).let { value -> value * value } })
    for (i in vector.indices) vector[i] = (vector[i] / norm).toFloat()
    return vector
}

private fun <T> LuceneIndex.use(block: (LuceneIndex) -> T): T {
    val index = this
    try {
        return block(index)
    } finally {
        index.close()
    }
}