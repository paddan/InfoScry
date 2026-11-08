package infoscry.image.analysis

import infoscry.domain.ContentUnitId
import infoscry.domain.DocumentId
import kotlin.test.Test
import kotlin.test.assertEquals

class ImageUnitTextTest {

    private fun edit(
        id: String,
        action: TagEditAction,
        tag: ImageTag,
        unit: ContentUnitId = ContentUnitId("unit-1"),
    ) = ImageTagEdit(
        editId = id,
        documentId = DocumentId("doc-1"),
        unitId = unit,
        action = action,
        tag = tag,
        createdBy = null,
        createdAt = "2026-11-07T10:00:00Z",
    )

    @Test
    fun `a removed tag stays out when the next analysis proposes it again`() {
        val proposed = ImageTag(TagCategory.PERSON, "man", "Jeffrey Epstein", TagSource.APPEARANCE)
        val edits = listOf(edit("e1", TagEditAction.REMOVE, proposed.copy(source = TagSource.MANUAL)))

        val again = ImageUnitText.tagsWithEdits(listOf(proposed.copy(source = TagSource.TEXT)), edits)

        assertEquals(emptyList(), again)
    }

    @Test
    fun `a confirmed appearance tag becomes manual and keeps its name`() {
        val proposed = ImageTag(TagCategory.PERSON, "man", "Jeffrey Epstein", TagSource.APPEARANCE)
        val edits = listOf(edit("e1", TagEditAction.CONFIRM, proposed.copy(source = TagSource.MANUAL)))

        val edited = ImageUnitText.tagsWithEdits(listOf(proposed), edits)

        assertEquals(listOf(ImageTag(TagCategory.PERSON, "man", "Jeffrey Epstein", TagSource.MANUAL)), edited)
    }

    @Test
    fun `an added tag is appended as manual and edits apply in creation order`() {
        val added = ImageTag(TagCategory.OBJECT, "bil", "Volvo PV444", TagSource.TEXT)
        val proposed = ImageTag(TagCategory.OBJECT, "bil", "Volvo PV444", TagSource.TEXT)
        val edits = listOf(
            edit("e1", TagEditAction.ADD, added),
            edit("e2", TagEditAction.REMOVE, added),
        )

        assertEquals(emptyList(), ImageUnitText.tagsWithEdits(listOf(proposed), edits), "added then removed")

        val addLast = edits.reversed()
        assertEquals(
            listOf(added.copy(source = TagSource.MANUAL)),
            ImageUnitText.tagsWithEdits(listOf(proposed), addLast),
            "removed then added",
        )
    }

    @Test
    fun `the unit text is caption, description, tag lines and context with blank lines between`() {
        val tags = listOf(
            ImageTag(TagCategory.PERSON, "man", "Jeffrey Epstein", TagSource.APPEARANCE),
            ImageTag(TagCategory.OBJECT, "bil", "Volvo PV444", TagSource.TEXT),
        )

        val text = ImageUnitText.of(
            caption = "Fig. 3",
            description = "Två män vid en bil.",
            tags = tags,
            context = null,
        )

        assertEquals(
            "Fig. 3\n\nTvå män vid en bil.\n\nman: possibly Jeffrey Epstein\nbil: Volvo PV444",
            text,
        )
    }

    @Test
    fun `a tag without a name is its label and a manual name is stated as itself`() {
        val tags = listOf(
            ImageTag(TagCategory.ANIMAL, "hund", null, TagSource.APPEARANCE),
            ImageTag(TagCategory.PLACE, "kyrka", "Uppsala domkyrka", TagSource.MANUAL),
        )

        assertEquals(
            "hund\nkyrka: Uppsala domkyrka",
            ImageUnitText.of(caption = null, description = null, tags = tags, context = null),
        )
    }

    @Test
    fun `empty parts and their blank lines are omitted and context follows the tags`() {
        val tags = listOf(ImageTag(TagCategory.PERSON, "man", null, TagSource.TEXT))

        assertEquals(
            "man\n\nSidan 14: en man vid en bil.",
            ImageUnitText.of(caption = "  ", description = null, tags = tags, context = "Sidan 14: en man vid en bil."),
        )
        assertEquals("", ImageUnitText.of(null, null, emptyList(), null))
    }

    @Test
    fun `the categories of a picture are its tags' categories`() {
        val tags = listOf(
            ImageTag(TagCategory.PERSON, "man", null, TagSource.TEXT),
            ImageTag(TagCategory.PERSON, "kvinna", null, TagSource.TEXT),
            ImageTag(TagCategory.OBJECT, "bil", null, TagSource.TEXT),
        )

        assertEquals(setOf(TagCategory.PERSON, TagCategory.OBJECT), ImageUnitText.categoriesOf(tags))
        assertEquals(emptySet(), ImageUnitText.categoriesOf(emptyList()))
    }
}
