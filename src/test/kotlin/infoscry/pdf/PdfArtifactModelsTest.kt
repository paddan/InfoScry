package infoscry.pdf

import infoscry.domain.CollectionId
import infoscry.domain.DocumentId
import infoscry.server.ApiJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PdfArtifactModelsTest {

    private val key = oracleSha256("artifact input list")

    private fun status(
        revisionId: String = "rev-1",
        key: String? = null,
        state: PdfArtifactState,
        selectablePages: Int = 0,
        totalPages: Int? = null,
        blankPages: Int = 0,
        unavailablePages: List<PdfPageResult> = emptyList(),
        reasonCode: String? = null,
    ) = PdfArtifactStatus(revisionId, key, state, selectablePages, totalPages, blankPages, unavailablePages, reasonCode)

    private fun unavailable(ordinal: Int, reason: String = "NO_ALIGNMENT") =
        PdfPageResult(ordinal, PdfPageCoverage.UNAVAILABLE, reason)

    // Acceptance 4: NOT_CREATED is a DTO state, not a durable job state.

    @Test
    fun `NOT_CREATED is not a durable job state and every other state is`() {
        assertFalse(PdfArtifactState.NOT_CREATED.isDurableJobState)
        listOf(PdfArtifactState.QUEUED, PdfArtifactState.BUILDING, PdfArtifactState.READY, PdfArtifactState.PARTIAL, PdfArtifactState.FAILED)
            .forEach { state -> assertTrue(state.isDurableJobState, state.name) }
    }

    @Test
    fun `NOT_CREATED carries no key, counts, unavailable pages or reason`() {
        assertFailsWith<IllegalArgumentException> { status(key = key, state = PdfArtifactState.NOT_CREATED) }
        assertFailsWith<IllegalArgumentException> { status(state = PdfArtifactState.NOT_CREATED, selectablePages = 1) }
        assertFailsWith<IllegalArgumentException> { status(state = PdfArtifactState.NOT_CREATED, blankPages = 1) }
        assertFailsWith<IllegalArgumentException> {
            status(state = PdfArtifactState.NOT_CREATED, unavailablePages = listOf(unavailable(0)))
        }
        assertFailsWith<IllegalArgumentException> { status(state = PdfArtifactState.NOT_CREATED, reasonCode = "NO_FILE") }
    }

    @Test
    fun `NOT_CREATED may leave the physical total unmeasured or know it`() {
        assertEquals(null, status(state = PdfArtifactState.NOT_CREATED).totalPages)
        assertEquals(3, status(state = PdfArtifactState.NOT_CREATED, totalPages = 3).totalPages)
    }

    // Acceptance 4: in-progress states need an input key; counts stay zero until a build finishes.

    @Test
    fun `QUEUED and BUILDING require a key and report no counts yet`() {
        listOf(PdfArtifactState.QUEUED, PdfArtifactState.BUILDING).forEach { state ->
            assertFailsWith<IllegalArgumentException>("$state without key") { status(state = state, totalPages = 3) }
            assertFailsWith<IllegalArgumentException>("$state with count") {
                status(key = key, state = state, selectablePages = 1, totalPages = 3)
            }
            assertEquals(null, status(key = key, state = state).totalPages, "unknown total is allowed while $state")
            assertEquals(3, status(key = key, state = state, totalPages = 3).totalPages)
        }
    }

    // Acceptance 4: READY and PARTIAL invariants.

    @Test
    fun `READY requires a key and a known total`() {
        assertFailsWith<IllegalArgumentException> { status(state = PdfArtifactState.READY, selectablePages = 2, totalPages = 2) }
        assertFailsWith<IllegalArgumentException> { status(key = key, state = PdfArtifactState.READY, selectablePages = 2) }
    }

    @Test
    fun `READY counts must account for every physical page exactly once`() {
        assertFailsWith<IllegalArgumentException> {
            status(key = key, state = PdfArtifactState.READY, selectablePages = 2, blankPages = 1, totalPages = 4)
        }
        assertFailsWith<IllegalArgumentException> {
            status(key = key, state = PdfArtifactState.READY, selectablePages = 3, blankPages = 1, totalPages = 3)
        }
        val ready = status(key = key, state = PdfArtifactState.READY, selectablePages = 2, blankPages = 1, totalPages = 3)
        assertEquals(3, ready.totalPages)
    }

    @Test
    fun `an all-blank document is READY with zero selectable pages`() {
        val ready = status(key = key, state = PdfArtifactState.READY, selectablePages = 0, blankPages = 4, totalPages = 4)
        assertEquals(0, ready.selectablePages)
    }

    @Test
    fun `READY rejects unavailable pages`() {
        assertFailsWith<IllegalArgumentException> {
            status(
                key = key,
                state = PdfArtifactState.READY,
                selectablePages = 1,
                blankPages = 0,
                totalPages = 2,
                unavailablePages = listOf(unavailable(1)),
            )
        }
    }

    @Test
    fun `PARTIAL requires the unavailable pages that explain the gap`() {
        assertFailsWith<IllegalArgumentException> {
            status(key = key, state = PdfArtifactState.PARTIAL, selectablePages = 1, totalPages = 2)
        }
        assertFailsWith<IllegalArgumentException> {
            status(
                key = key,
                state = PdfArtifactState.PARTIAL,
                selectablePages = 1,
                totalPages = 2,
                unavailablePages = listOf(PdfPageResult(1, PdfPageCoverage.OCR, null)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            status(
                key = key,
                state = PdfArtifactState.PARTIAL,
                selectablePages = 1,
                totalPages = 2,
                unavailablePages = listOf(unavailable(5)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            status(
                key = key,
                state = PdfArtifactState.PARTIAL,
                selectablePages = 1,
                totalPages = 3,
                unavailablePages = listOf(unavailable(1), unavailable(1)),
            )
        }
        val partial = status(
            key = key,
            state = PdfArtifactState.PARTIAL,
            selectablePages = 1,
            totalPages = 2,
            unavailablePages = listOf(unavailable(1)),
        )
        assertEquals(1, partial.unavailablePages.size)
    }

    // Acceptance 4: FAILED is safe and produces no counted pages.

    @Test
    fun `FAILED carries a safe reason and no counted pages`() {
        assertFailsWith<IllegalArgumentException> { status(state = PdfArtifactState.FAILED) }
        assertFailsWith<IllegalArgumentException> { status(state = PdfArtifactState.FAILED, reasonCode = "raw provider body") }
        assertFailsWith<IllegalArgumentException> {
            status(state = PdfArtifactState.FAILED, reasonCode = "DISK_FULL", selectablePages = 1, totalPages = 2)
        }
        assertEquals("DISK_FULL", status(state = PdfArtifactState.FAILED, reasonCode = "DISK_FULL").reasonCode)
    }

    @Test
    fun `reason codes and keys are validated on every state`() {
        assertFailsWith<IllegalArgumentException> { status(state = PdfArtifactState.FAILED, reasonCode = "disk full") }
        assertFailsWith<IllegalArgumentException> {
            status(key = "ABC", state = PdfArtifactState.QUEUED)
        }
        assertFailsWith<IllegalArgumentException> {
            status(key = key.uppercase(), state = PdfArtifactState.QUEUED)
        }
        assertFailsWith<IllegalArgumentException> { status(revisionId = " ", state = PdfArtifactState.NOT_CREATED) }
    }

    // Acceptance 4: exact wire form under the existing ApiJson serializer.

    @Test
    fun `NOT_CREATED wire form omits null fields and decodes them back`() {
        val notCreated = status(state = PdfArtifactState.NOT_CREATED)
        val json = ApiJson.encodeToString(PdfArtifactStatus.serializer(), notCreated)
        assertEquals(
            """{"revisionId":"rev-1","state":"NOT_CREATED","selectablePages":0,"blankPages":0,"unavailablePages":[]}""",
            json,
        )
        assertEquals(notCreated, ApiJson.decodeFromString(PdfArtifactStatus.serializer(), json))
    }

    @Test
    fun `READY wire form lists the known total and omits the null reason`() {
        val ready = status(key = key, state = PdfArtifactState.READY, selectablePages = 2, blankPages = 1, totalPages = 3)
        val json = ApiJson.encodeToString(PdfArtifactStatus.serializer(), ready)
        assertEquals(
            """{"revisionId":"rev-1","key":"$key","state":"READY","selectablePages":2,"totalPages":3,"blankPages":1,"unavailablePages":[]}""",
            json,
        )
        assertEquals(ready, ApiJson.decodeFromString(PdfArtifactStatus.serializer(), json))
    }

    @Test
    fun `PARTIAL wire form round-trips its unavailable pages with reasons`() {
        val partial = status(
            key = key,
            state = PdfArtifactState.PARTIAL,
            selectablePages = 1,
            totalPages = 2,
            unavailablePages = listOf(unavailable(1, "NO_ALIGNMENT")),
        )
        val json = ApiJson.encodeToString(PdfArtifactStatus.serializer(), partial)
        assertTrue(json.contains("""{"ordinal":1,"coverage":"UNAVAILABLE","reasonCode":"NO_ALIGNMENT"}"""), json)
        assertEquals(partial, ApiJson.decodeFromString(PdfArtifactStatus.serializer(), json))
    }

    @Test
    fun `every valid state round-trips through ApiJson`() {
        val states = listOf(
            status(state = PdfArtifactState.NOT_CREATED, totalPages = 3),
            status(key = key, state = PdfArtifactState.QUEUED),
            status(key = key, state = PdfArtifactState.BUILDING, totalPages = 3),
            status(key = key, state = PdfArtifactState.READY, selectablePages = 3, totalPages = 3),
            status(key = key, state = PdfArtifactState.PARTIAL, selectablePages = 1, totalPages = 2, unavailablePages = listOf(unavailable(1))),
            status(state = PdfArtifactState.FAILED, reasonCode = "DISK_FULL"),
        )
        states.forEach { state ->
            val json = ApiJson.encodeToString(PdfArtifactStatus.serializer(), state)
            assertEquals(state, ApiJson.decodeFromString(PdfArtifactStatus.serializer(), json), json)
        }
    }

    @Test
    fun `QUEUED and FAILED wire forms are exact`() {
        val queued = status(key = key, state = PdfArtifactState.QUEUED)
        assertEquals(
            """{"revisionId":"rev-1","key":"$key","state":"QUEUED","selectablePages":0,"blankPages":0,"unavailablePages":[]}""",
            ApiJson.encodeToString(PdfArtifactStatus.serializer(), queued),
        )
        val failed = status(state = PdfArtifactState.FAILED, reasonCode = "DISK_FULL")
        assertEquals(
            """{"revisionId":"rev-1","state":"FAILED","selectablePages":0,"blankPages":0,"unavailablePages":[],"reasonCode":"DISK_FULL"}""",
            ApiJson.encodeToString(PdfArtifactStatus.serializer(), failed),
        )
    }

    // Acceptance 4: the request DTO.

    @Test
    fun `create request always emits retry and defaults it when absent`() {
        val request = CreateSelectablePdfRequest("rev-1")
        assertEquals("""{"revisionId":"rev-1","retry":false}""", ApiJson.encodeToString(CreateSelectablePdfRequest.serializer(), request))
        assertEquals(request, ApiJson.decodeFromString(CreateSelectablePdfRequest.serializer(), """{"revisionId":"rev-1"}"""))
        assertFailsWith<IllegalArgumentException> { CreateSelectablePdfRequest(" ") }
    }

    // Acceptance 5: the artifact key is one frozen function over one input list.

    private fun keyInput(
        collection: String = "collection-1",
        document: String = "document-1",
        original: String = oracleSha256("original bytes"),
        revision: String = "rev-1",
        renderer: String = "selectable-pdf-renderer-1",
        font: String = oracleSha256("font bytes"),
        pageMode: PdfPageMode = PdfPageMode.OCR,
        pageText: String? = oracleSha256("page text"),
        pageImage: String? = oracleSha256("page image"),
        pageLayout: String? = oracleSha256("page layout"),
    ) = SelectablePdfKeyInput(
        collectionId = CollectionId(collection),
        documentId = DocumentId(document),
        managedOriginalSha256 = original,
        revisionId = revision,
        rendererVersion = renderer,
        fontSha256 = font,
        pages = listOf(
            SelectablePdfPageKeyInput(0, pageMode, pageText, pageImage, pageLayout),
            SelectablePdfPageKeyInput(1, PdfPageMode.NATIVE, null, null, null),
        ),
    )

    @Test
    fun `artifact key is a stable SHA-256 over the whole input list`() {
        val base = selectablePdfArtifactKey(keyInput())
        assertTrue(base.matches(Regex("[0-9a-f]{64}")), base)
        assertEquals(base, selectablePdfArtifactKey(keyInput()))
        val variants = listOf(
            keyInput(collection = "collection-2"),
            keyInput(document = "document-2"),
            keyInput(original = oracleSha256("another original")),
            keyInput(revision = "rev-2"),
            keyInput(renderer = "selectable-pdf-renderer-2"),
            keyInput(font = oracleSha256("another font")),
            keyInput(pageMode = PdfPageMode.UNAVAILABLE),
            keyInput(pageText = oracleSha256("edited text")),
            keyInput(pageImage = oracleSha256("re-rendered image")),
            keyInput(pageLayout = oracleSha256("re-positioned layout")),
        )
        variants.forEachIndexed { index, variant ->
            assertNotEquals(base, selectablePdfArtifactKey(variant), "variant $index must change the key")
        }
    }

    @Test
    fun `artifact key input rejects non-contiguous pages and malformed hashes`() {
        assertFailsWith<IllegalArgumentException> {
            SelectablePdfKeyInput(
                CollectionId("c"),
                DocumentId("d"),
                oracleSha256("o"),
                "rev-1",
                "renderer",
                oracleSha256("f"),
                listOf(SelectablePdfPageKeyInput(1, PdfPageMode.NATIVE, null, null, null)),
            )
        }
        assertFailsWith<IllegalArgumentException> { keyInput(original = "not-a-hash") }
        assertFailsWith<IllegalArgumentException> { keyInput(pageText = "XYZ") }
    }
}
