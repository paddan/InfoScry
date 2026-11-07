package infoscry.image

/**
 * How much of a document's imagery an import or rescan extracts.
 *
 * The [id] is the stable wire and storage form (`off`, `embedded`, `all`); parsing refuses anything else rather
 * than defaulting, so a mistyped mode cannot silently change what is extracted.
 */
enum class ImageExtractionMode(val id: String) {
    /** No image units are produced. */
    OFF("off"),

    /** Pictures stored in the document are extracted; page regions are not located. */
    EMBEDDED("embedded"),

    /** Embedded pictures and page regions located by layout analysis are extracted. */
    ALL("all");

    companion object {
        /** The mode with the given [id]. Throws [IllegalArgumentException] for any other value. */
        fun parse(id: String): ImageExtractionMode =
            entries.firstOrNull { it.id == id }
                ?: throw IllegalArgumentException("unknown image extraction mode '$id'")
    }
}
