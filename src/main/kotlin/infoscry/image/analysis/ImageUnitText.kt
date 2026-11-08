package infoscry.image.analysis

/**
 * The text an image unit is indexed with, and the edits people made to its tags.
 *
 * The text is the caption, a blank line, the description, a blank line, one line per tag, a blank line, and the
 * page context; an absent part and the blank line that would separate it are omitted. Nothing is truncated: the
 * chunker splits it, so a long description or many tags remain searchable in full.
 *
 * An `APPEARANCE` name is written `possibly <name>`, because that is exactly what it is: the model recognised
 * it, no text in the archive states it. A `TEXT` or `MANUAL` name is written as itself.
 */
object ImageUnitText {

    /**
     * [tags] with [edits] applied in the order people made them.
     *
     * A `REMOVE` drops every tag with the same category, label and name — whatever source the analysis gave it,
     * because the person's decision is about the thing, not about who suggested it — and so keeps a removed tag
     * out when a later analysis proposes it again. A `CONFIRM` turns such a match into a manual tag, keeping the
     * name as stated fact. An `ADD` appends a manual tag.
     */
    fun tagsWithEdits(tags: List<ImageTag>, edits: List<ImageTagEdit>): List<ImageTag> {
        val applied = tags.toMutableList()
        for (edit in edits) {
            when (edit.action) {
                TagEditAction.REMOVE -> applied.removeAll { it.sameThing(edit.tag) }
                TagEditAction.CONFIRM -> {
                    val index = applied.indexOfFirst { it.sameThing(edit.tag) }
                    if (index >= 0) applied[index] = applied[index].copy(source = TagSource.MANUAL)
                }
                TagEditAction.ADD -> applied += edit.tag.copy(source = TagSource.MANUAL)
            }
        }
        return applied
    }

    /** The indexed text of one image unit, as described above. */
    fun of(caption: String?, description: String?, tags: List<ImageTag>, context: String?): String =
        listOfNotNull(
            caption?.takeIf { it.isNotBlank() },
            description?.takeIf { it.isNotBlank() },
            tags.takeIf { it.isNotEmpty() }?.joinToString("\n", transform = ::lineOf),
            context?.takeIf { it.isNotBlank() },
        ).joinToString("\n\n")

    /** The categories this picture's tags put it in, which is what a picture filter matches. */
    fun categoriesOf(tags: List<ImageTag>): Set<TagCategory> = tags.mapTo(linkedSetOf()) { it.category }

    /** One tag as an indexed line: its label, and its name when it has one. */
    fun lineOf(tag: ImageTag): String {
        val name = tag.name ?: return tag.label
        return when (tag.source) {
            TagSource.APPEARANCE -> "${tag.label}: possibly $name"
            TagSource.TEXT, TagSource.MANUAL -> "${tag.label}: $name"
        }
    }

    private fun ImageTag.sameThing(other: ImageTag): Boolean =
        category == other.category && label == other.label && name == other.name
}
