package infoscry.jobs

import kotlinx.serialization.Serializable

/**
 * Which files of a folder import are kept, by file-name extension (ticket 06).
 *
 * An include list keeps only the files whose extension is in it; an exclude list keeps every file whose
 * extension is not in it, including files with no extension at all. The two lists are mutually exclusive, and
 * both empty means no filter. Entries are stored normalised: lower case, without leading dots, and without
 * repeats, so the stored filter is exactly what [admits] compares against.
 *
 * The filter is part of the queued [ImportJobPayload], so an attempt resumed after a restart applies the same
 * filter the import was asked for.
 */
@Serializable
data class ExtensionFilter(
    val include: List<String> = emptyList(),
    val exclude: List<String> = emptyList(),
) {

    init {
        require(include.isEmpty() || exclude.isEmpty()) { INCLUDE_AND_EXCLUDE }
        require((include + exclude).all(::isNormalized)) { "an extension filter must hold normalised extensions" }
    }

    /** Whether a file named [fileName] is kept by this filter. */
    fun admits(fileName: String): Boolean {
        if (include.isEmpty() && exclude.isEmpty()) return true
        val extension = extensionOf(fileName)
        return if (include.isNotEmpty()) {
            extension != null && extension in include
        } else {
            extension == null || extension !in exclude
        }
    }

    companion object {

        /** The filter of an import that names no extensions: every file is kept. */
        val NONE = ExtensionFilter()

        private const val INCLUDE_AND_EXCLUDE = "include and exclude cannot be used together"

        /**
         * The filter a request names, normalised. Refuses both lists at once, and refuses a blank entry or one
         * that names a path, because either would read as a filter a person did not mean.
         */
        fun of(include: List<String>, exclude: List<String>): ExtensionFilter {
            require(include.isEmpty() || exclude.isEmpty()) { INCLUDE_AND_EXCLUDE }
            return ExtensionFilter(include = normalizeAll(include), exclude = normalizeAll(exclude))
        }

        private fun normalizeAll(entries: List<String>): List<String> = entries.map { entry ->
            val normalized = entry.trim().trimStart('.').lowercase()
            require(normalized.isNotEmpty()) { "an extension must not be blank" }
            require(normalized.none { it == '/' || it == '\\' }) { "an extension must not name a path" }
            normalized
        }.distinct()

        private fun isNormalized(entry: String): Boolean =
            entry.isNotEmpty() && entry == entry.trimStart('.').lowercase() && entry.none { it == '/' || it == '\\' }

        /**
         * The extension of [fileName]: the text after its last dot, lower-cased. A name with no dot, or whose only
         * dot is its first character (a dot-file such as `.gitignore`), has no extension.
         */
        internal fun extensionOf(fileName: String): String? {
            val dot = fileName.lastIndexOf('.')
            if (dot <= 0) return null
            return fileName.substring(dot + 1).lowercase().takeIf { it.isNotEmpty() }
        }
    }
}
