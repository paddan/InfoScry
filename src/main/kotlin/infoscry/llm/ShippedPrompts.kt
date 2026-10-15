package infoscry.llm

import infoscry.domain.CollectionId

/**
 * A prompt a collection can use for one role. The shipped Default has [ShippedPrompts.DEFAULT_ID] and
 * [builtIn] true; stored prompts come from [infoscry.storage.CollectionPromptStore].
 */
data class CollectionPrompt(
    val id: String,
    val collectionId: CollectionId,
    val role: LlmPromptRole,
    val name: String,
    val body: String,
    val version: Int,
    val builtIn: Boolean,
)

/** The shipped Default prompt bodies, read from the classpath; they are never rows and never editable. */
object ShippedPrompts {
    const val DEFAULT_ID = "default"
    const val DEFAULT_NAME = "Default"
    private const val SHIPPED_VERSION = 1

    /** The shipped Default body for [role], read from `prompts/<role>.md` on the classpath. */
    fun body(role: LlmPromptRole): String {
        val resource = "prompts/" + role.name.lowercase() + ".md"
        return ShippedPrompts::class.java.classLoader.getResourceAsStream(resource)
            ?.use { it.readBytes().decodeToString() }
            ?: error("prompt resource $resource is missing from the classpath")
    }

    /** The shipped Default as a [CollectionPrompt] for [collectionId] and [role]. */
    fun prompt(collectionId: CollectionId, role: LlmPromptRole): CollectionPrompt = CollectionPrompt(
        id = DEFAULT_ID,
        collectionId = collectionId,
        role = role,
        name = DEFAULT_NAME,
        body = body(role),
        version = SHIPPED_VERSION,
        builtIn = true,
    )
}
