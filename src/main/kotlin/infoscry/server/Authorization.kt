package infoscry.server

import infoscry.AppContext
import infoscry.account.CollectionRole
import infoscry.account.Principal
import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import infoscry.domain.Collection
import infoscry.domain.CollectionId
import infoscry.domain.JobId
import io.ktor.http.HttpMethod
import io.ktor.server.application.ApplicationCall
import io.ktor.util.AttributeKey

/** What a route requires of its caller, beyond being signed in where that applies. */
sealed interface AccessRule {

    /** Anyone, signed in or not. */
    data object Public : AccessRule

    /** Any signed-in user; the handler may narrow what it answers (a list of what the caller can see). */
    data object SignedIn : AccessRule

    /** An administrator. */
    data object Administrator : AccessRule

    /** [role] in the collection the path names in its `{id}` segment. */
    data class InCollection(val role: CollectionRole) : AccessRule

    /** [role] in the collection of the job the path names in its `{id}` segment. */
    data class OfJob(val role: CollectionRole) : AccessRule

    /** The handler decides, because the collection is named in the body or the rule needs a lookup the path cannot give. */
    data class InHandler(val reason: String) : AccessRule
}

/** A member whose role is too low for what they asked. */
class CollectionRoleRequiredException(needed: CollectionRole) : ApiFaultException(
    ApiStatus.FORBIDDEN,
    "COLLECTION_ROLE_REQUIRED",
    "this needs the ${needed.name.lowercase()} role in the collection",
)

/** The answer for a collection the caller may not know exists: the same as for one that does not exist. */
private fun hiddenCollection(reference: String): NoSuchElementException =
    NoSuchElementException("no usable collection named or identified by '$reference'")

private val READER = CollectionRole.READER
private val EDITOR = CollectionRole.EDITOR
private val OWNER = CollectionRole.OWNER

/**
 * Every API route and what it requires, in one place.
 *
 * The request guard enforces each rule it can decide from the path; an [AccessRule.InHandler] route calls
 * [requireCollectionRole] itself. `AuthorizationTableTest` walks the routing tree and fails for a route
 * missing here, so a new route cannot be added without deciding who may use it. A request whose route is
 * missing is refused unless the caller is an administrator.
 */
val ACCESS_RULES: Map<Pair<HttpMethod, String>, AccessRule> = buildMap {
    fun rule(method: HttpMethod, path: String, access: AccessRule) = put(method to path, access)
    val get = HttpMethod.Get
    val post = HttpMethod.Post
    val put = HttpMethod.Put
    val patch = HttpMethod.Patch
    val delete = HttpMethod.Delete

    // Sign-in and the caller's own account.
    rule(get, "/api/session", AccessRule.Public)
    rule(post, "/api/auth/sign-in", AccessRule.Public)
    rule(post, "/api/auth/sign-out", AccessRule.Public)
    rule(get, "/api/me", AccessRule.SignedIn)
    rule(patch, "/api/me", AccessRule.SignedIn)
    rule(post, "/api/me/password", AccessRule.SignedIn)
    rule(get, "/api/me/tokens", AccessRule.SignedIn)
    rule(post, "/api/me/tokens", AccessRule.SignedIn)
    rule(delete, "/api/me/tokens/{tokenId}", AccessRule.SignedIn)
    rule(get, "/api/users/directory", AccessRule.SignedIn)

    // Users, LLM and OCR profiles are the administrators'; reading the profiles is everyone's, to choose one.
    rule(get, "/api/users", AccessRule.Administrator)
    rule(post, "/api/users", AccessRule.Administrator)
    rule(patch, "/api/users/{userId}", AccessRule.Administrator)
    rule(post, "/api/users/{userId}/password-reset", AccessRule.Administrator)
    rule(get, "/api/llm/presets", AccessRule.SignedIn)
    rule(get, "/api/llm/catalog", AccessRule.Administrator)
    rule(get, "/api/llm/profiles", AccessRule.SignedIn)
    rule(post, "/api/llm/profiles", AccessRule.Administrator)
    rule(put, "/api/llm/profiles/{id}", AccessRule.Administrator)
    rule(delete, "/api/llm/profiles/{id}", AccessRule.Administrator)
    rule(post, "/api/llm/profiles/{id}/probe", AccessRule.Administrator)
    rule(get, "/api/llm/defaults/{role}", AccessRule.SignedIn)
    rule(put, "/api/llm/defaults/{role}", AccessRule.Administrator)
    rule(get, "/api/ocr/profiles", AccessRule.SignedIn)
    rule(post, "/api/ocr/profiles", AccessRule.Administrator)
    rule(post, "/api/ocr/profiles/from-llm", AccessRule.Administrator)
    rule(patch, "/api/ocr/profiles/{profileId}", AccessRule.Administrator)
    rule(delete, "/api/ocr/profiles/{profileId}", AccessRule.Administrator)
    rule(post, "/api/ocr/profiles/{profileId}/probe", AccessRule.Administrator)
    rule(get, "/api/ocr/llm-profiles", AccessRule.SignedIn)

    // Collections: anyone may create one and becomes its owner; the list shows what the caller may see.
    rule(get, "/api/collections", AccessRule.SignedIn)
    rule(post, "/api/collections", AccessRule.SignedIn)
    rule(patch, "/api/collections/{id}", AccessRule.InCollection(OWNER))
    rule(delete, "/api/collections/{id}", AccessRule.InCollection(OWNER))
    rule(get, "/api/collections/{id}/ignore-patterns", AccessRule.InCollection(READER))
    rule(put, "/api/collections/{id}/ignore-patterns", AccessRule.InCollection(OWNER))
    rule(patch, "/api/collections/{id}/ocr-languages", AccessRule.InCollection(OWNER))
    rule(get, "/api/collections/{id}/members", AccessRule.InCollection(READER))
    rule(put, "/api/collections/{id}/members/{userId}", AccessRule.InCollection(OWNER))
    rule(delete, "/api/collections/{id}/members/{userId}", AccessRule.InCollection(OWNER))
    rule(get, "/api/collections/{id}/reading-methods", AccessRule.InCollection(READER))
    rule(get, "/api/collections/{id}/imports", AccessRule.InCollection(READER))

    rule(get, "/api/collections/{id}/prompts", AccessRule.InCollection(READER))
    rule(post, "/api/collections/{id}/prompts", AccessRule.InCollection(EDITOR))
    rule(put, "/api/collections/{id}/prompts/{promptId}", AccessRule.InCollection(EDITOR))
    rule(delete, "/api/collections/{id}/prompts/{promptId}", AccessRule.InCollection(EDITOR))
    rule(put, "/api/collections/{id}/prompt-defaults/{role}", AccessRule.InCollection(EDITOR))
    rule(get, "/api/collections/{id}/corrections", AccessRule.InCollection(READER))
    rule(put, "/api/collections/{id}/corrections/{ruleId}", AccessRule.InCollection(EDITOR))
    rule(delete, "/api/collections/{id}/corrections/{ruleId}", AccessRule.InCollection(EDITOR))
    rule(post, "/api/collections/{id}/corrections/preview", AccessRule.InCollection(EDITOR))
    rule(post, "/api/collections/{id}/corrections/apply", AccessRule.InCollection(EDITOR))
    rule(get, "/api/collections/{id}/glossary", AccessRule.InCollection(READER))
    rule(put, "/api/collections/{id}/glossary/{groupId}", AccessRule.InCollection(EDITOR))
    rule(delete, "/api/collections/{id}/glossary/{groupId}", AccessRule.InCollection(EDITOR))

    // Documents and their sources.
    rule(get, "/api/collections/{id}/documents", AccessRule.InCollection(READER))
    rule(get, "/api/collections/{id}/documents/summary", AccessRule.InCollection(READER))
    rule(get, "/api/collections/{id}/documents/{documentId}", AccessRule.InCollection(READER))
    rule(post, "/api/collections/{id}/documents/delete", AccessRule.InCollection(EDITOR))
    rule(post, "/api/collections/{id}/documents/retry", AccessRule.InCollection(EDITOR))
    rule(get, "/api/collections/{id}/documents/{documentId}/original", AccessRule.InCollection(READER))
    rule(get, "/api/collections/{id}/sources/{sourceId}", AccessRule.InCollection(READER))
    rule(get, "/api/collections/{id}/sources/{sourceId}/reading", AccessRule.InCollection(READER))
    rule(get, "/api/collections/{id}/documents/{documentId}/page-review", AccessRule.InCollection(READER))
    rule(get, "/api/collections/{id}/documents/{documentId}/page-review/image", AccessRule.InCollection(READER))
    rule(get, "/api/collections/{id}/documents/{documentId}/selectable-pdf", AccessRule.InCollection(READER))
    rule(post, "/api/collections/{id}/documents/{documentId}/selectable-pdf", AccessRule.InCollection(EDITOR))
    rule(get, "/api/collections/{id}/documents/{documentId}/selectable-pdf/file", AccessRule.InCollection(READER))
    val ocr = "/api/collections/{id}/documents/{documentId}/ocr"
    rule(post, "$ocr/preview", AccessRule.InCollection(EDITOR))
    rule(post, "$ocr/rescan", AccessRule.InCollection(EDITOR))
    rule(get, "$ocr/operations", AccessRule.InCollection(READER))
    rule(get, "$ocr/operations/{operationId}", AccessRule.InCollection(READER))
    rule(post, "$ocr/operations/{operationId}/cancel", AccessRule.InCollection(EDITOR))
    rule(post, "$ocr/operations/{operationId}/resume", AccessRule.InCollection(EDITOR))
    rule(get, "$ocr/revisions", AccessRule.InCollection(READER))
    rule(post, "$ocr/restore", AccessRule.InCollection(EDITOR))
    rule(post, "$ocr/decisions", AccessRule.InCollection(EDITOR))
    rule(get, "$ocr/restores/{restoreId}", AccessRule.InCollection(READER))

    // Asking: the collection's readers; each conversation is its owner's alone (checked in the handler).
    rule(post, "/api/ask", AccessRule.InHandler("the collection is named in the body"))
    rule(get, "/api/collections/{id}/asks", AccessRule.InCollection(READER))
    rule(get, "/api/collections/{id}/investigations", AccessRule.InCollection(READER))
    rule(get, "/api/collections/{id}/investigations/{conversationId}", AccessRule.InCollection(READER))
    rule(delete, "/api/collections/{id}/conversations/{conversationId}", AccessRule.InCollection(READER))
    rule(post, "/api/investigations", AccessRule.InHandler("the collection is named in the body"))
    rule(post, "/api/investigations/{id}/continue", AccessRule.InHandler("the conversation decides the collection and owner"))
    rule(post, "/api/investigations/{id}/cancel", AccessRule.InHandler("only the conversation's owner"))

    // Searching, importing and rebuilding name their collection in the query or body.
    rule(get, "/api/search", AccessRule.InHandler("the collection is a query parameter"))
    rule(post, "/api/search", AccessRule.InHandler("the collection is named in the body"))
    rule(post, "/api/reindex", AccessRule.InHandler("one collection's owner, or an administrator for all"))
    rule(post, "/api/imports/preview", AccessRule.InHandler("an editor of the collection in the body, choosing server paths"))
    rule(post, "/api/imports", AccessRule.InHandler("an editor of the collection in the body, choosing server paths"))
    rule(post, "/api/imports/pick", AccessRule.Administrator)

    // Jobs and deletions follow their collection; the lists show what the caller may see.
    rule(get, "/api/jobs", AccessRule.SignedIn)
    rule(get, "/api/jobs/{id}", AccessRule.OfJob(READER))
    rule(get, "/api/jobs/{id}/items", AccessRule.OfJob(READER))
    rule(get, "/api/jobs/{id}/events", AccessRule.OfJob(READER))
    rule(post, "/api/jobs/{id}/cancel", AccessRule.OfJob(EDITOR))
    rule(get, "/api/deletions", AccessRule.SignedIn)
    rule(get, "/api/deletions/{operationId}", AccessRule.InHandler("its requester or an administrator"))
}

/** The rule for a request, matching literal segments before `{parameters}`; with the values of the parameters. */
internal fun ruleFor(method: HttpMethod, decodedPath: String): Pair<AccessRule, Map<String, String>>? {
    val segments = decodedPath.split('/').filter { it.isNotEmpty() }
    return ACCESS_RULES.entries
        .asSequence()
        .filter { (key, _) -> key.first == method }
        .mapNotNull { (key, rule) ->
            val pattern = key.second.split('/').filter { it.isNotEmpty() }
            if (pattern.size != segments.size) return@mapNotNull null
            val parameters = mutableMapOf<String, String>()
            var literals = 0
            for ((index, part) in pattern.withIndex()) {
                if (part.startsWith("{") && part.endsWith("}")) {
                    parameters[part.substring(1, part.length - 1)] = segments[index]
                } else if (part == segments[index]) {
                    literals++
                } else {
                    return@mapNotNull null
                }
            }
            Triple(literals, rule, parameters)
        }
        .maxByOrNull { it.first }
        ?.let { it.second to it.third }
}

/**
 * The collection roles of one caller: an administrator acts as owner of every collection, anyone else has
 * the role their membership gives them, or none.
 */
class CollectionAccess(private val context: AppContext) {

    /** The caller's role in [collectionId], or `null` when they are not a member. */
    fun roleOf(principal: Principal, collectionId: CollectionId): CollectionRole? =
        if (principal.isAdmin) CollectionRole.OWNER else context.memberships.roleOf(collectionId, principal.user.id)

    /** The collections the caller may see; `null` means every collection (an administrator). */
    fun visibleCollections(principal: Principal): Set<CollectionId>? =
        if (principal.isAdmin) null else context.memberships.rolesOf(principal.user.id).keys

    /**
     * The collection [reference] (an id or a name) when the caller has at least [needed] there.
     *
     * A collection the caller is not a member of answers exactly as one that does not exist; a member whose
     * role is too low gets [CollectionRoleRequiredException]. An administrator is answered `null` for a
     * collection that does not exist, so the route itself says what it says about a missing collection.
     */
    fun require(principal: Principal, reference: String, needed: CollectionRole): Collection? {
        val collection = context.collections.get(CollectionId(reference))
            ?: context.collections.list().firstOrNull { it.name.equals(reference, ignoreCase = true) }
        if (collection == null) {
            if (principal.isAdmin) return null
            throw hiddenCollection(reference)
        }
        val role = roleOf(principal, collection.id) ?: throw hiddenCollection(reference)
        if (!role.allows(needed)) throw CollectionRoleRequiredException(needed)
        return collection
    }

    /** Applies a path rule, throwing the refusal a route would. */
    fun enforce(principal: Principal, rule: AccessRule, parameters: Map<String, String>) {
        when (rule) {
            AccessRule.Public, AccessRule.SignedIn, is AccessRule.InHandler -> Unit
            AccessRule.Administrator -> if (!principal.isAdmin) throw AdministratorRequiredException()
            is AccessRule.InCollection -> require(principal, checkNotNull(parameters["id"]), rule.role)
            is AccessRule.OfJob -> {
                val job = runCatching { JobId(checkNotNull(parameters["id"])) }.getOrNull()?.let(context.jobs::get) ?: return
                val collectionId = job.collectionId
                if (collectionId == null) {
                    if (!principal.isAdmin) throw NoSuchElementException("no job with id ${job.id.value}")
                    return
                }
                val role = roleOf(principal, collectionId) ?: throw NoSuchElementException("no job with id ${job.id.value}")
                if (!role.allows(rule.role)) throw CollectionRoleRequiredException(rule.role)
            }
        }
    }
}

private val ACCESS_KEY = AttributeKey<CollectionAccess>("infoscry.collectionAccess")

internal fun ApplicationCall.attachAccess(access: CollectionAccess) = attributes.put(ACCESS_KEY, access)

private val ApplicationCall.access: CollectionAccess get() = attributes[ACCESS_KEY]

/** The collection [reference] names, when the caller has [needed] there; for a route that reads it from its body. */
fun ApplicationCall.requireCollectionRole(reference: String, needed: CollectionRole): Collection? =
    access.require(principal, reference, needed)

/** The caller's role in [collectionId], or `null` when they are not a member. */
fun ApplicationCall.collectionRole(collectionId: CollectionId): CollectionRole? = access.roleOf(principal, collectionId)

/** The collections the caller may see; `null` means every collection. */
fun ApplicationCall.visibleCollections(): Set<CollectionId>? = access.visibleCollections(principal)
