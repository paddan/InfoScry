package infoscry.image

import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.NormalizedBox
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageIdentityTest {

    private val document = DocumentId("doc-1")
    private val parent = ContentUnitId("parent-1")
    private val sha = "a".repeat(64)
    private val box = NormalizedBox(0.123, 0.2, 0.5, 0.6)

    @Test
    fun `the same inputs give the same unit id on every run`() {
        val first = ImageIdentity.unitId(document, parent, ImageSourceKind.EMBEDDED, sha, null, 1)
        val second = ImageIdentity.unitId(document, parent, ImageSourceKind.EMBEDDED, sha, null, 1)
        assertEquals(first, second)
        assertTrue(first.value.matches(Regex("[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")))
    }

    @Test
    fun `a different occurrence, kind or sha256 gives a different unit id`() {
        val base = ImageIdentity.unitId(document, parent, ImageSourceKind.EMBEDDED, sha, null, 1)
        assertNotEquals(base, ImageIdentity.unitId(document, parent, ImageSourceKind.EMBEDDED, sha, null, 2))
        assertNotEquals(base, ImageIdentity.unitId(document, parent, ImageSourceKind.STANDALONE, sha, null, 1))
        assertNotEquals(base, ImageIdentity.unitId(document, parent, ImageSourceKind.EMBEDDED, "b".repeat(64), null, 1))
        assertNotEquals(base, ImageIdentity.unitId(DocumentId("doc-2"), parent, ImageSourceKind.EMBEDDED, sha, null, 1))
    }

    @Test
    fun `a page region's box is rounded to three decimals before hashing`() {
        val rounded = ImageIdentity.unitId(document, parent, ImageSourceKind.PAGE_REGION, null, NormalizedBox(0.12341, 0.2, 0.5, 0.6), 1)
        val same = ImageIdentity.unitId(document, parent, ImageSourceKind.PAGE_REGION, null, NormalizedBox(0.12344, 0.2, 0.5, 0.6), 1)
        val other = ImageIdentity.unitId(document, parent, ImageSourceKind.PAGE_REGION, null, NormalizedBox(0.124, 0.2, 0.5, 0.6), 1)
        assertEquals(rounded, same)
        assertNotEquals(rounded, other)
        assertEquals(
            ImageIdentity.unitId(document, parent, ImageSourceKind.PAGE_REGION, null, box, 1),
            ImageIdentity.unitId(document, parent, ImageSourceKind.PAGE_REGION, null, box.rounded(), 1),
        )
    }

    @Test
    fun `each kind requires its own identifying input`() {
        assertFailsWith<IllegalArgumentException> {
            ImageIdentity.unitId(document, parent, ImageSourceKind.EMBEDDED, null, null, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            ImageIdentity.unitId(document, parent, ImageSourceKind.PAGE_REGION, null, null, 1)
        }
        assertFailsWith<IllegalArgumentException> {
            ImageIdentity.unitId(document, parent, ImageSourceKind.EMBEDDED, sha, null, 0)
        }
    }

    @Test
    fun `ordinals stay after the text units and stable per parent and index`() {
        assertEquals(1_000_014_001, ImageIdentity.ordinal(14, 2))
        assertEquals(1_000_000_000, ImageIdentity.ordinal(0, 1))
        assertNull(ImageIdentity.ordinal(1_147_483, 1))
        assertEquals(2_147_482_999, ImageIdentity.ordinal(1_147_482, 1_000))
        assertFalse(ImageIdentity.isImageOrdinal(999_999_999))
        assertTrue(ImageIdentity.isImageOrdinal(1_000_014_001))
    }

    @Test
    fun `image extraction mode parses its stable ids and rejects others`() {
        assertEquals(listOf("off", "embedded", "all"), ImageExtractionMode.entries.map { it.id })
        assertEquals(ImageExtractionMode.ALL, ImageExtractionMode.parse("all"))
        assertFailsWith<IllegalArgumentException> { ImageExtractionMode.parse("ALL") }
        assertFailsWith<IllegalArgumentException> { ImageExtractionMode.parse("") }
    }
}
