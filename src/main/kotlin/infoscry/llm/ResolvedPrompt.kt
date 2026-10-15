package infoscry.llm

/**
 * The prompt an Ask or Investigate run uses, snapshotted for the conversation. [id] is null for the
 * shipped Default; a stored prompt carries its own id.
 */
data class ResolvedPrompt(
    val id: String?,
    val name: String,
    val version: Int,
    val body: String,
)
