package infoscry.jobs

import java.util.regex.Pattern
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * A collection's ignore patterns (ticket 07): files, and whole folders, that an import never attempts.
 *
 * The syntax is the part of `.gitignore` a person expects to use, matched against the path *below the folder that
 * was imported* (a file named directly is matched by its own name alone):
 *
 * - `*` matches any run of characters inside one name, `?` exactly one character; neither crosses a `/`.
 * - `**` as a whole path segment matches any number of directories, including none (`**` `/x`, `a/` `**` `/x`, `a/` `**`).
 * - A pattern with no `/` except a trailing one matches a name at any depth; a pattern with a `/` at the start or in
 *   the middle is anchored to the imported folder.
 * - A trailing `/` matches directories only. An ignored directory is not walked, so nothing inside it is looked at, and
 *   a file inside it cannot be re-included.
 * - A leading `!` re-includes what an earlier pattern ignored; the last matching pattern wins.
 * - A line starting with `#` is a comment and a blank line is nothing. `\#` and `\!` at the start name a literal
 *   `#` or `!`; `\` escapes any other character the same way.
 *
 * Deliberate differences from git, recorded in the ticket: matching ignores letter case (a macOS or Windows volume does,
 * and `Thumbs.db` should find `thumbs.db`), character classes (`[abc]`) are not supported and their brackets are
 * literal, and trailing spaces are always trimmed.
 *
 * A list is validated when it is built, so an invalid pattern is refused when a person saves it and never when an
 * import runs. Comments are kept in [patterns] so a saved list reads back as it was written; they match nothing.
 */
@Serializable
data class IgnorePatterns(val patterns: List<String> = emptyList()) {

    @Transient
    private val rules: List<Rule> = compile(patterns)

    /**
     * Whether [relativePath] (slash-separated, below the imported folder) is ignored. [isDirectory] says whether it
     * names a directory, which only a pattern with a trailing `/` can match. A path is ignored when any directory
     * above it is.
     */
    fun ignores(relativePath: String, isDirectory: Boolean = false): Boolean {
        if (rules.isEmpty()) return false
        val segments = relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() && it != "." }
        if (segments.isEmpty()) return false
        for (end in 1 until segments.size) {
            if (decide(segments.subList(0, end).joinToString("/"), isDirectory = true)) return true
        }
        return decide(segments.joinToString("/"), isDirectory)
    }

    private fun decide(path: String, isDirectory: Boolean): Boolean {
        var ignored = false
        for (rule in rules) {
            if (rule.directoryOnly && !isDirectory) continue
            if (rule.regex.matcher(path).matches()) ignored = !rule.negated
        }
        return ignored
    }

    private class Rule(val regex: Pattern, val negated: Boolean, val directoryOnly: Boolean)

    companion object {

        const val MAX_PATTERN_LENGTH = 500
        const val MAX_PATTERNS = 500

        /** No patterns: nothing is ignored. Also what a payload written before this feature reads as. */
        val NONE = IgnorePatterns()

        /** The list a new collection starts with. */
        val DEFAULTS: List<String> =
            listOf(".DS_Store", "._*", "Thumbs.db", "desktop.ini", "~$*", "*.tmp", ".git/", "node_modules/")

        /**
         * The list for [lines] as a person entered them: trimmed, blank lines dropped, and every remaining line
         * validated. Throws [IllegalArgumentException] naming the offending line.
         */
        fun of(lines: List<String>): IgnorePatterns =
            IgnorePatterns(lines.map { it.trim() }.filter { it.isNotEmpty() })

        private fun compile(patterns: List<String>): List<Rule> {
            require(patterns.size <= MAX_PATTERNS) { "an ignore list holds at most $MAX_PATTERNS patterns" }
            return patterns.mapNotNull(::parse)
        }

        /** The rule for one line, null for a comment. */
        private fun parse(line: String): Rule? {
            fun refuse(reason: String): Nothing = throw IllegalArgumentException("ignore pattern '${line.take(60)}': $reason")
            require(line.length <= MAX_PATTERN_LENGTH) { "an ignore pattern is at most $MAX_PATTERN_LENGTH characters long" }
            if (line.any { it.code < 0x20 || it.code == 0x7f }) refuse("must not contain control characters")
            if (line.isEmpty()) refuse("must not be blank")
            if (line.startsWith("#")) return null

            var body = line
            val negated = body.startsWith("!")
            if (negated) body = body.substring(1)
            val directoryOnly = body.endsWith("/")
            body = body.trimEnd('/')
            val leadingSlash = body.startsWith("/")
            if (leadingSlash) body = body.substring(1)
            if (body.isEmpty()) refuse("names nothing")
            if (trailingBackslashes(body) % 2 == 1) refuse("must not end with a lone backslash")

            val segments = body.split('/')
            if (segments.any { it.isEmpty() }) refuse("must not contain an empty path segment")
            if (segments.any { it == ".." || it == "." }) refuse("must not contain '.' or '..' segments")

            val anchored = leadingSlash || segments.size > 1
            val regex = StringBuilder(if (anchored) "" else "(?:.*/)?")
            segments.forEachIndexed { index, segment ->
                val last = index == segments.lastIndex
                if (segment == "**") {
                    regex.append(if (last) ".*" else "(?:.*/)?")
                } else {
                    regex.append(translate(segment))
                    if (!last) regex.append('/')
                }
            }
            return Rule(
                Pattern.compile(regex.toString(), Pattern.CASE_INSENSITIVE or Pattern.UNICODE_CASE),
                negated,
                directoryOnly,
            )
        }

        private fun trailingBackslashes(text: String): Int = text.length - text.trimEnd('\\').length

        /** One path segment's wildcards as a regular expression that cannot cross a `/`. */
        private fun translate(segment: String): String {
            val out = StringBuilder()
            var index = 0
            while (index < segment.length) {
                when (val char = segment[index]) {
                    '*' -> {
                        while (index + 1 < segment.length && segment[index + 1] == '*') index++
                        out.append("[^/]*")
                    }
                    '?' -> out.append("[^/]")
                    '\\' -> {
                        index++
                        out.append(Pattern.quote(segment[index].toString()))
                    }
                    else -> out.append(Pattern.quote(char.toString()))
                }
                index++
            }
            return out.toString()
        }
    }
}
