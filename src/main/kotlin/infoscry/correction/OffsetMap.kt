package infoscry.correction

/**
 * Where an offset of a text lands once [edits] are applied to it.
 *
 * An offset outside every edit moves by the length changes of the edits before it. An offset inside an edit has
 * no exact counterpart, so a range's start maps to the edit's new start and a range's end to its new end: a
 * range that touches a replaced word grows or shrinks to the whole replacement rather than splitting it.
 */
class OffsetMap(edits: List<TextEdit>) {

    private val sorted = edits.sortedBy { it.start }

    /** The new start of each edit, in the same order as [sorted]. */
    private val newStarts: IntArray

    init {
        var delta = 0
        newStarts = IntArray(sorted.size) { index ->
            val edit = sorted[index]
            require(index == 0 || sorted[index - 1].endExclusive <= edit.start) { "edits must not overlap" }
            (edit.start + delta).also { delta += edit.replacement.length - (edit.endExclusive - edit.start) }
        }
    }

    fun mapStart(offset: Int): Int = map(offset, toEnd = false)

    fun mapEnd(offset: Int): Int = map(offset, toEnd = true)

    private fun map(offset: Int, toEnd: Boolean): Int {
        var delta = 0
        for ((index, edit) in sorted.withIndex()) {
            when {
                offset <= edit.start -> return offset + delta
                offset < edit.endExclusive ->
                    return if (toEnd) newStarts[index] + edit.replacement.length else newStarts[index]
                else -> delta += edit.replacement.length - (edit.endExclusive - edit.start)
            }
        }
        return offset + delta
    }
}
