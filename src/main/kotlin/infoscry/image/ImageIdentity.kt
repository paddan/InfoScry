package infoscry.image

import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import infoscry.domain.NormalizedBox
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID

/** Where an image unit's pixels come from, which decides what identifies it within its parent. */
enum class ImageSourceKind {
    /** An image stored in the parent's content, identified by the SHA-256 of its bytes. */
    EMBEDDED,

    /** A region cropped from a page raster, identified by its box on that page. */
    PAGE_REGION,

    /** An imported picture, which is the whole managed copy, identified by its SHA-256. */
    STANDALONE,
}

/**
 * The stable identity and ordinal of image units.
 *
 * An image unit's ID is a name-based UUID (version 5, RFC 4122) over a canonical, line-delimited form of its
 * inputs, so the same picture in the same parent gets the same ID on every import and rescan. Its ordinal sits
 * after every text unit's ordinal, so an image never shifts a text unit's position in the reading order.
 */
object ImageIdentity {

    /** The first ordinal of any image unit; text unit ordinals are below it. */
    const val ORDINAL_BASE = 1_000_000_000

    /** The most images one parent unit can carry. */
    const val MAX_PER_PARENT = 1_000

    /** The highest parent ordinal whose images still fit in an [Int] ordinal. */
    const val MAX_PARENT_ORDINAL = 1_147_482

    /** A fixed namespace for every image unit ID. Changing it renames every image unit. */
    private val NAMESPACE: UUID = UUID.fromString("b7e3c1d2-5a4f-4e8b-9d61-2c3f8a9e0b17")

    /**
     * The unit ID of one image.
     *
     * [sha256] is required for [ImageSourceKind.EMBEDDED] and [ImageSourceKind.STANDALONE], and [box] for
     * [ImageSourceKind.PAGE_REGION]. [occurrence] is one-based and tells apart equal images in one parent,
     * in reading order; it is hashed for every kind so that two regions with the same box stay distinct.
     */
    fun unitId(
        documentId: DocumentId,
        parentUnitId: ContentUnitId,
        kind: ImageSourceKind,
        sha256: String?,
        box: NormalizedBox?,
        occurrence: Int,
    ): ContentUnitId {
        require(occurrence >= 1) { "an image occurrence is one-based, was $occurrence" }
        when (kind) {
            ImageSourceKind.EMBEDDED, ImageSourceKind.STANDALONE ->
                require(!sha256.isNullOrBlank()) { "a ${kind.name} image needs its SHA-256" }
            ImageSourceKind.PAGE_REGION ->
                require(box != null) { "a page region needs its box on the page" }
        }
        // One field per line, in a fixed order; the same canonical style as ExtractionFingerprint.
        val fields = buildList {
            add("document=${documentId.value}")
            add("parent=${parentUnitId.value}")
            add("kind=${kind.name}")
            sha256?.let { add("sha256=$it") }
            box?.rounded()?.let { add("box=${it.left},${it.top},${it.right},${it.bottom}") }
            add("occurrence=$occurrence")
        }
        val name = fields.joinToString("\n").toByteArray(Charsets.UTF_8)
        return ContentUnitId(uuidV5(NAMESPACE, name).toString())
    }

    /**
     * The ordinal of the [index]th image (one-based) of a parent whose ordinal is [parentOrdinal], or null when
     * the parent is too late in the reading order for its images to fit an [Int].
     */
    fun ordinal(parentOrdinal: Int, index: Int): Int? {
        require(parentOrdinal >= 0) { "a parent ordinal is not negative, was $parentOrdinal" }
        require(index in 1..MAX_PER_PARENT) { "an image index is within 1..$MAX_PER_PARENT, was $index" }
        if (parentOrdinal > MAX_PARENT_ORDINAL) return null
        return ORDINAL_BASE + parentOrdinal * MAX_PER_PARENT + (index - 1)
    }

    /** Whether [ordinal] belongs to an image unit rather than a text unit. */
    fun isImageOrdinal(ordinal: Int): Boolean = ordinal >= ORDINAL_BASE

    /** RFC 4122 section 4.3: SHA-1 over the namespace bytes and the name, with version and variant bits set. */
    private fun uuidV5(namespace: UUID, name: ByteArray): UUID {
        val namespaceBytes = ByteBuffer.allocate(16)
            .putLong(namespace.mostSignificantBits)
            .putLong(namespace.leastSignificantBits)
            .array()
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update(namespaceBytes)
        digest.update(name)
        val bytes = digest.digest().copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x50).toByte()
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
        val buffer = ByteBuffer.wrap(bytes)
        return UUID(buffer.long, buffer.long)
    }
}
