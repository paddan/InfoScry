package infoscry.search

import infoscry.domain.Chunk
import infoscry.domain.ChunkId
import infoscry.domain.CollectionId
import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.SourceLocation
import infoscry.storage.Database
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * The search index: one row per searchable chunk in `search_chunks`, with BM25 and vector search over them.
 */
class SearchIndexTest {

    private lateinit var directory: Path
    private lateinit var database: Database

    @BeforeTest
    fun openDatabase() {
        directory = Files.createTempDirectory("infoscry-search-index")
        database = Database(directory)
        listOf(COLLECTION, OTHER_COLLECTION).forEach { collection ->
            database.transaction { connection ->
                connection.prepareStatement(
                    "INSERT INTO collections (id, name, created_at, updated_at) VALUES (?, ?, 't', 't') ON CONFLICT DO NOTHING",
                ).use {
                    it.setString(1, collection.value)
                    it.setString(2, collection.value)
                    it.executeUpdate()
                }
            }
        }
    }

    @AfterTest
    fun closeDatabase() {
        database.close()
        directory.toFile().deleteRecursively()
    }

    /** A document row for every document a test indexes, since the index's rows belong to real documents. */
    private fun ensureDocument(collection: CollectionId, documentId: DocumentId) {
        database.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO documents (id, collection_id, sha256, media_type, original_filename, original_path, " +
                    "size_bytes, status, created_at, updated_at) VALUES (?, ?, ?, 'text/plain', 'f.txt', '/f.txt', 1, " +
                    "'COMPLETE', 't', 't') ON CONFLICT DO NOTHING",
            ).use {
                it.setString(1, documentId.value)
                it.setString(2, collection.value)
                it.setString(3, documentId.value)
                it.executeUpdate()
            }
        }
    }

    private fun openIndex(identity: IndexIdentity = IDENTITY): SearchIndex = SearchIndex(database, identity).open()

    @Test
    fun `a scope filter naming more documents than the clause ceiling searches`() {
        // 1 100 ids in the scope, one query: the scope is one bound array, so its size is not a clause count.
        val documentIds = (1..1_100).map { DocumentId("doc-$it") }
        openIndex().let { index ->
            runBlocking {
                documentIds.take(INDEXED_DOCUMENTS).forEach { documentId ->
                    index.replaceDocument(chunksFor(documentId, listOf("budget nightfall report")))
                }
            }

            val hits = index.searchKeyword(
                COLLECTION,
                "budget",
                documentIds = documentIds.map { it.value }.toSet(),
                limit = 5,
            )

            assertEquals(INDEXED_DOCUMENTS, hits.size, "the scope must match the indexed documents rather than refuse")
            assertTrue(hits.all { it.text.contains("budget") })
        }
    }




    @Test
    fun `query syntax keeps the meaning the query parser gives it`() {
        openIndex().let { index ->
            index.replaceDocument(chunksFor(DOCUMENT, listOf("allmän handling om förvaltningsrätten")))
            index.replaceDocument(chunksFor(OTHER_DOCUMENT, listOf("handling allmän utan domstol")))
            fun matches(query: String) = index.searchKeyword(COLLECTION, query, limit = 10).map { it.documentId }.toSet()

            assertEquals(setOf(DOCUMENT.value), matches("\"allmän handling\""), "a phrase keeps its word order")
            assertEquals(setOf(DOCUMENT.value), matches("förvaltn*"), "a prefix is folded like the text")
            assertEquals(setOf(DOCUMENT.value), matches("forvaltningsratten"), "folding finds the accented word")
            assertEquals(setOf(DOCUMENT.value, OTHER_DOCUMENT.value), matches("handling"))
            assertEquals(setOf(OTHER_DOCUMENT.value), matches("handling -förvaltningsrätten"), "an exclusion excludes")
            assertEquals(setOf(DOCUMENT.value), matches("+allmän +förvaltningsrätten"))
            assertEquals(setOf(OTHER_DOCUMENT.value), matches("dom?tol"), "a wildcard inside a word matches one character")
            assertEquals(setOf(DOCUMENT.value), matches("forvaltningsratte~1"), "a fuzzy term allows one edit")
            assertEquals(emptySet(), matches("-handling"), "exclusions alone match nothing")
        }
    }

    @Test
    fun `replacing a document twice leaves one set of chunks`() {
        openIndex().let { index ->
            val rows = chunksFor(DOCUMENT, listOf("first chunk", "second chunk", "third chunk"))
            runBlocking { index.replaceDocument(rows) }

            val again = chunksFor(DOCUMENT, listOf("first chunk", "second chunk", "third chunk"))
            runBlocking { index.replaceDocument(again) }

            assertEquals(3, index.chunkCount(COLLECTION, DOCUMENT), "a replacement must not duplicate chunks")
        }
    }

    @Test
    fun `deleting a document removes all of its chunks`() {
        openIndex().let { index ->
            runBlocking { index.replaceDocument(chunksFor(DOCUMENT, listOf("only", "two", "chunks"))) }
            assertEquals(3, index.chunkCount(COLLECTION, DOCUMENT))

            runBlocking { index.deleteDocument(DOCUMENT) }

            assertEquals(0, index.chunkCount(COLLECTION, DOCUMENT))
        }
    }

    @Test
    fun `keyword search returns the stored identity, snippet and label filtered by collection`() {
        openIndex().let { index ->
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
    fun `a question mark ending a word and a colon are plain text, not query syntax`() {
        openIndex().let { index ->
            runBlocking {
                index.replaceDocument(
                    chunksFor(DOCUMENT, listOf("tvångsvård av unga", "lagen (1962:700)", "SFS 2010:800 gäller")),
                )
            }
            fun texts(query: String) = index.searchKeyword(COLLECTION, query, limit = 10).map { it.text }.toSet()

            assertEquals(setOf("tvångsvård av unga"), texts("unga?"))
            assertEquals(setOf("tvångsvård av unga"), texts("vem beslutar om tvångsvård av unga?"))
            assertEquals(setOf("lagen (1962:700)"), texts("1962:700"))
            assertEquals(setOf("SFS 2010:800 gäller"), texts("+SFS +2010:800"))
            assertEquals(setOf("SFS 2010:800 gäller"), texts("\"SFS 2010:800\""))
            // Syntax the search box offers keeps working: a wildcard inside a word, a prefix, phrases and operators.
            assertEquals(setOf("tvångsvård av unga"), texts("u?ga"))
            assertEquals(setOf("tvångsvård av unga"), texts("tvångs*"))
            assertEquals(setOf("lagen (1962:700)"), texts("lagen AND 1962"))
            assertEquals(setOf("SFS 2010:800 gäller"), texts("2010 -lagen"))
        }
    }

    private fun glossaryOf(collection: CollectionId, vararg terms: infoscry.glossary.GlossaryTermInput) =
        infoscry.glossary.Glossary(
            collection,
            listOf(infoscry.glossary.GlossaryGroup("g-${collection.value}", collection, infoscry.glossary.GlossaryRules.normalize(terms.toList()), "t", "t")),
        )

    private fun term(text: String, prefix: Boolean = false) = infoscry.glossary.GlossaryTermInput(text, prefix)

    @Test
    fun `a glossary expansion finds the other names in its collection only`() {
        openIndex().let { index ->
            runBlocking {
                index.replaceDocument(chunksFor(DOCUMENT, listOf("brev från Stockholm", "Sthlms hamn")))
                index.replaceDocument(chunksFor(OTHER_DOCUMENT, listOf("resa till Stockholm"), collection = OTHER_COLLECTION))
            }
            val glossaries = mapOf(COLLECTION to glossaryOf(COLLECTION, term("Stockholm"), term("Sthlm", prefix = true)))

            val scoped = index.expandKeyword(COLLECTION, "Sthlm", glossaries)
            assertEquals(listOf("g-nightfall"), scoped.groups.map { it.id })
            assertEquals(2, index.searchKeyword(COLLECTION, "Sthlm", limit = 10, expansion = scoped).size)
            val reverse = index.expandKeyword(COLLECTION, "Stockholm", glossaries)
            assertEquals(
                setOf("brev från Stockholm", "Sthlms hamn"),
                index.searchKeyword(COLLECTION, "Stockholm", limit = 10, expansion = reverse).map { it.text }.toSet(),
            )

            val other = index.expandKeyword(OTHER_COLLECTION, "Sthlm", glossaries)
            assertTrue(other.groups.isEmpty())
            assertTrue(index.searchKeyword(OTHER_COLLECTION, "Sthlm", limit = 10, expansion = other).isEmpty())

            val everywhere = index.expandKeyword(null, "Stockholm", glossaries)
            assertEquals(
                setOf("brev från Stockholm", "Sthlms hamn", "resa till Stockholm"),
                index.searchKeyword(null, "Stockholm", limit = 10, expansion = everywhere).map { it.text }.toSet(),
            )
            val sthlmEverywhere = index.expandKeyword(null, "Sthlm", glossaries)
            assertEquals(
                setOf("brev från Stockholm", "Sthlms hamn"),
                index.searchKeyword(null, "Sthlm", limit = 10, expansion = sthlmEverywhere).map { it.text }.toSet(),
                "another collection's Stockholm is not found by this collection's Sthlm",
            )
        }
    }

    @Test
    fun `an expansion keeps the whole query within the clause ceiling`() {
        openIndex().let { index ->
            val groups = (1..300).map { n ->
                infoscry.glossary.GlossaryGroup("g$n", COLLECTION, infoscry.glossary.GlossaryRules.normalize(listOf(term("w$n"), term("x$n"))), "t", "t")
            }
            val query = (1..900).joinToString(" ") { "w$it" }

            val expansion = index.expandKeyword(COLLECTION, query, mapOf(COLLECTION to infoscry.glossary.Glossary(COLLECTION, groups)))

            assertTrue(expansion.truncated)
            assertTrue(GlossaryQueryRewriter.clauseCount(expansion.query) <= SearchIndex.MAX_QUERY_TOKENS)
            index.searchKeyword(COLLECTION, query, limit = 1, expansion = expansion)
        }
    }

    @Test
    fun `an alternative's word anchors a query`() {
        openIndex().let { index ->
            runBlocking { index.replaceDocument(chunksFor(DOCUMENT, listOf("brev från Stockholm"))) }

            assertFalse(index.hasLexicalAnchor("Sthlm", COLLECTION))
            assertTrue(index.hasLexicalAnchor("Sthlm", COLLECTION, extraTokens = listOf("stockholm")))
        }
    }

    @Test
    fun `a lexical anchor is a token prefix of an indexed term in the given scope`() {
        openIndex().let { index ->
            runBlocking {
                index.replaceDocument(chunksFor(DOCUMENT, listOf("watergate resignation")))
            }

            assertTrue(index.hasLexicalAnchor("watergate", COLLECTION))
            assertTrue(index.hasLexicalAnchor("waterg", COLLECTION), "a word being typed must still anchor")
            assertTrue(index.hasLexicalAnchor("resign", COLLECTION), "an inflected form must still anchor")
            assertFalse(index.hasLexicalAnchor("sdkjfhsdkfhksdhfk", COLLECTION), "nonsense has no foothold")
            assertFalse(index.hasLexicalAnchor("wa", COLLECTION), "a two-letter prefix is not evidence")
            assertFalse(index.hasLexicalAnchor("watergate", OTHER_COLLECTION), "the anchor is scoped")
        }
    }

    @Test
    fun `vector search returns the closest vectors and filters by collection`() {
        openIndex().let { index ->
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
        openIndex().let { index ->
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
        openIndex().let { index ->
            runBlocking { index.replaceDocument(chunksFor(DOCUMENT, listOf("anything"))) }
        }

        val other = openIndex(IDENTITY.copy(modelFingerprint = "some-other-model"))
        assertEquals(SearchIndex.SCHEMA_VERSION, other.schemaStatus.assertRebuildRequired().schemaVersion)
        assertTrue(other.schemaStatus.reasons().any { it.contains("model") })

        other.recordIdentity()
        assertEquals(SearchIndex.SCHEMA_VERSION, other.schemaStatus.assertReady().schemaVersion)
    }


    /** One row with its vector given explicitly, so a test can decide what is closest to what. */
    private fun row(
        documentId: DocumentId,
        text: String,
        vector: FloatArray,
        revisionId: String? = null,
    ): DocumentRow = ensureDocument(COLLECTION, documentId).let { DocumentRow(
        collectionId = COLLECTION,
        documentId = documentId,
        unitId = ContentUnitId.new(),
        chunk = Chunk(
            id = ChunkId.new(),
            contentUnitId = ContentUnitId.new(),
            ordinal = 0,
            text = text,
            startOffset = 0,
            endOffset = text.length,
            tokenCount = text.length,
            tokenStart = 0,
            tokenEnd = (text.length - 1).coerceAtLeast(0),
        ),
        locator = SourceLocation.TextLines(1, 1),
        locatorLabel = LOCATOR_LABEL,
        vector = vector,
        revisionId = revisionId,
    ) }

    private fun chunksFor(
        documentId: DocumentId,
        texts: List<String>,
        collection: CollectionId = COLLECTION,
    ): List<DocumentRow> = texts.mapIndexed { ordinal, text ->
        ensureDocument(collection, documentId)
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

    private companion object {
        const val INDEXED_DOCUMENTS = 5

        val IDENTITY = IndexIdentity(
            schemaVersion = SearchIndex.SCHEMA_VERSION,
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
