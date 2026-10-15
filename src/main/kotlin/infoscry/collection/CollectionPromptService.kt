package infoscry.collection

import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import infoscry.domain.CollectionId
import infoscry.llm.CollectionPrompt
import infoscry.llm.LlmPromptRole
import infoscry.llm.ResolvedPrompt
import infoscry.llm.ShippedPrompts
import infoscry.storage.CollectionPromptStore
import infoscry.storage.MutationCoordinator
import java.sql.SQLException
import java.util.Locale

/** A stored prompt's name is already used in this collection and role by a prompt with a different body. */
class PromptNameTaken : ApiFaultException(
    ApiStatus.CONFLICT,
    "PROMPT_NAME_TAKEN",
    "a prompt with this name already exists",
)

/** A prompt request the rules refuse: a reserved or invalid name or body, or a reference outside the collection and role. */
class InvalidPromptRequest(message: String) : ApiFaultException(ApiStatus.BAD_REQUEST, "INVALID_REQUEST", message)

/** One role's prompts: the shipped Default first, then stored prompts by name, and the collection default id. */
data class RolePrompts(val defaultId: String, val prompts: List<CollectionPrompt>)

/** Everything a client needs to show a collection's prompts. */
data class CollectionPrompts(val coreRules: String, val ask: RolePrompts, val investigate: RolePrompts)

/** The outcome of [CollectionPromptService.create]; [created] is false when an identical prompt already existed. */
data class PromptWrite(val prompt: CollectionPrompt, val created: Boolean)

/**
 * The rules for a collection's Ask and Investigate prompts: validation, the reserved `Default` name,
 * name uniqueness per collection and role, repeat handling and default selection. Every write runs
 * under shared mutation admission and requires an active collection. Prompt bodies never appear in
 * exception messages.
 */
class CollectionPromptService(
    private val store: CollectionPromptStore,
    private val collections: CollectionService,
    private val mutations: MutationCoordinator,
    private val coreRules: () -> String,
) {

    fun list(collectionId: CollectionId): CollectionPrompts {
        collections.requireActive(collectionId)
        return CollectionPrompts(
            coreRules = coreRules(),
            ask = rolePrompts(collectionId, LlmPromptRole.ASK),
            investigate = rolePrompts(collectionId, LlmPromptRole.INVESTIGATE),
        )
    }

    suspend fun create(
        collectionId: CollectionId,
        role: LlmPromptRole,
        name: String,
        body: String,
    ): PromptWrite = mutations.withMutation {
        collections.requireMutationsAllowed()
        collections.requireActive(collectionId)
        val cleanName = validName(name)
        validBody(body)
        val existing = store.list(collectionId, role).firstOrNull { sameName(it.name, cleanName) }
        if (existing != null) {
            if (existing.body == body) return@withMutation PromptWrite(existing, created = false)
            throw PromptNameTaken()
        }
        PromptWrite(withNameConflictTranslated { store.insert(collectionId, role, cleanName, body) }, created = true)
    }

    suspend fun update(
        collectionId: CollectionId,
        promptId: String,
        name: String,
        body: String,
    ): CollectionPrompt = mutations.withMutation {
        collections.requireMutationsAllowed()
        collections.requireActive(collectionId)
        if (promptId == ShippedPrompts.DEFAULT_ID) {
            throw InvalidPromptRequest("the Default prompt cannot be edited")
        }
        val current = storedPrompt(collectionId, promptId)
        val cleanName = validName(name)
        validBody(body)
        if (cleanName == current.name && body == current.body) return@withMutation current
        val clash = store.list(collectionId, current.role).firstOrNull {
            it.id != promptId && sameName(it.name, cleanName)
        }
        if (clash != null) throw PromptNameTaken()
        withNameConflictTranslated { store.update(promptId, cleanName, body) }
    }

    /** Deletes a stored prompt. A prompt that is already gone is not an error. */
    suspend fun delete(collectionId: CollectionId, promptId: String) {
        mutations.withMutation {
            collections.requireMutationsAllowed()
            collections.requireActive(collectionId)
            if (promptId == ShippedPrompts.DEFAULT_ID) {
                throw InvalidPromptRequest("the Default prompt cannot be deleted")
            }
            val stored = store.findById(promptId) ?: return@withMutation
            if (stored.collectionId != collectionId) {
                throw InvalidPromptRequest("no prompt with that id in this collection")
            }
            store.delete(promptId)
            Unit
        }
    }

    /** Selects the collection default for [role] and returns its id; [ShippedPrompts.DEFAULT_ID] stands for Default. */
    suspend fun setDefault(collectionId: CollectionId, role: LlmPromptRole, promptId: String): String =
        mutations.withMutation {
            collections.requireMutationsAllowed()
            collections.requireActive(collectionId)
            val target: String? = if (promptId == ShippedPrompts.DEFAULT_ID) {
                null
            } else {
                storedPrompt(collectionId, promptId, role).id
            }
            if (store.defaultPromptId(collectionId, role) != target) {
                store.setDefaultPromptId(collectionId, role, target)
            }
            target ?: ShippedPrompts.DEFAULT_ID
        }

    /**
     * The prompt a run uses. [ref] is null for the collection default, otherwise an id or a name in
     * this collection and role; `Default` is always accepted by name.
     */
    fun resolve(collectionId: CollectionId, role: LlmPromptRole, ref: String?): ResolvedPrompt {
        collections.requireActive(collectionId)
        if (ref == null) {
            val defaultId = store.defaultPromptId(collectionId, role) ?: return shippedResolved(collectionId, role)
            return storedPrompt(collectionId, defaultId, role).resolved()
        }
        val trimmed = ref.trim()
        if (trimmed == ShippedPrompts.DEFAULT_ID || sameName(trimmed, ShippedPrompts.DEFAULT_NAME)) {
            return shippedResolved(collectionId, role)
        }
        val match = store.list(collectionId, role).firstOrNull { it.id == trimmed || sameName(it.name, trimmed) }
            ?: throw InvalidPromptRequest("no prompt with that name or id in this collection and role")
        return match.resolved()
    }

    private fun rolePrompts(collectionId: CollectionId, role: LlmPromptRole): RolePrompts = RolePrompts(
        defaultId = store.defaultPromptId(collectionId, role) ?: ShippedPrompts.DEFAULT_ID,
        prompts = listOf(ShippedPrompts.prompt(collectionId, role)) + store.list(collectionId, role),
    )

    /** A stored prompt that belongs to [collectionId], and to [role] when one is given. */
    private fun storedPrompt(collectionId: CollectionId, promptId: String, role: LlmPromptRole? = null): CollectionPrompt {
        val stored = store.findById(promptId)
        if (stored == null || stored.collectionId != collectionId || (role != null && stored.role != role)) {
            throw InvalidPromptRequest("no prompt with that id in this collection and role")
        }
        return stored
    }

    private fun shippedResolved(collectionId: CollectionId, role: LlmPromptRole): ResolvedPrompt {
        val shipped = ShippedPrompts.prompt(collectionId, role)
        return ResolvedPrompt(id = null, name = shipped.name, version = shipped.version, body = shipped.body)
    }

    private fun CollectionPrompt.resolved(): ResolvedPrompt = ResolvedPrompt(id, name, version, body)

    /** The trimmed name, after checking it is 1 to 80 characters and not the reserved name. */
    private fun validName(name: String): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) throw InvalidPromptRequest("a prompt name must not be blank")
        if (trimmed.codePointCount(0, trimmed.length) > MAX_NAME_CHARS) {
            throw InvalidPromptRequest("a prompt name must be at most $MAX_NAME_CHARS characters")
        }
        if (sameName(trimmed, ShippedPrompts.DEFAULT_NAME)) {
            throw InvalidPromptRequest("the name ${ShippedPrompts.DEFAULT_NAME} is reserved")
        }
        return trimmed
    }

    private fun validBody(body: String) {
        if (body.isBlank()) throw InvalidPromptRequest("a prompt body must not be blank")
        if (body.codePointCount(0, body.length) > MAX_BODY_CHARS) {
            throw InvalidPromptRequest("a prompt body must be at most $MAX_BODY_CHARS characters")
        }
    }

    /**
     * The schema's unique index on (collection, role, name) is the final authority on duplicate names. The
     * pre-check can miss a row another process committed after it read, so the index violation is
     * answered as [PromptNameTaken] rather than as a raw SQL error.
     */
    private fun <T> withNameConflictTranslated(write: () -> T): T = try {
        write()
    } catch (failure: SQLException) {
        if (failure.message?.contains("UNIQUE", ignoreCase = true) == true) throw PromptNameTaken() else throw failure
    }

    private fun sameName(a: String, b: String): Boolean =
        a.trim().lowercase(Locale.ROOT) == b.trim().lowercase(Locale.ROOT)

    private companion object {
        const val MAX_NAME_CHARS = 80
        const val MAX_BODY_CHARS = 20_000
    }
}
