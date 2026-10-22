package infoscry.correction

import infoscry.domain.CollectionId

/**
 * One standing correction of a collection: every whole-word occurrence of [find] in its documents' text reads
 * [replace]. Without [exactCase] the match ignores case and the replacement follows the writing it replaces.
 */
data class CorrectionRule(
    val id: String,
    val collectionId: CollectionId,
    val find: String,
    val replace: String,
    val exactCase: Boolean,
    val createdAt: String,
    val updatedAt: String,
)
