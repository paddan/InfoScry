package infoscry.server

import infoscry.AppContext
import infoscry.account.CollectionRole
import infoscry.account.UserId
import infoscry.storage.MembershipRow
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Routing
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable

@Serializable
data class MemberWire(
    val userId: String,
    val username: String,
    val displayName: String,
    val active: Boolean,
    val role: CollectionRole,
    val addedAt: String,
)

@Serializable
data class MembersResponse(val members: List<MemberWire>, val callerRole: CollectionRole?)

@Serializable
data class MemberResponse(val member: MemberWire)

@Serializable
data class PutMemberRequest(val role: CollectionRole)

/** One person an owner can add: never more than the name a colleague already sees. */
@Serializable
data class DirectoryEntry(val id: String, val username: String, val displayName: String)

@Serializable
data class DirectoryResponse(val users: List<DirectoryEntry>)

private fun MembershipRow.toWire() = MemberWire(userId.value, username, displayName, active, role, addedAt)

/**
 * A collection's members. Who may call each route is in [ACCESS_RULES]: every member reads the list, owners
 * change it. The answer also carries the caller's own role, so the interface shows only what they may do.
 */
fun Routing.configureMembershipRoutes(context: AppContext) {
    get("/api/collections/{id}/members") {
        call.handle {
            val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
            call.respondJson(
                HttpStatusCode.OK,
                MembersResponse(context.memberships.members(collection.id).map { it.toWire() }, call.collectionRole(collection.id)),
            )
        }
    }

    put("/api/collections/{id}/members/{userId}") {
        call.handle {
            val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
            val request = call.receiveJson<PutMemberRequest>()
            val member = context.mutations.withMutation {
                context.memberships.put(collection.id, call.memberId(), request.role, call.principal.user.id)
            }
            call.respondJson(HttpStatusCode.OK, MemberResponse(member.toWire()))
        }
    }

    delete("/api/collections/{id}/members/{userId}") {
        call.handle {
            val collection = context.collectionService.requireActiveByNameOrId(call.collectionId().value)
            context.mutations.withMutation { context.memberships.remove(collection.id, call.memberId()) }
            call.respond(HttpStatusCode.NoContent)
        }
    }

    get("/api/users/directory") {
        call.handle {
            val users = context.accounts.listUsers().filter { it.active }
                .map { DirectoryEntry(it.id.value, it.username, it.displayName) }
            call.respondJson(HttpStatusCode.OK, DirectoryResponse(users))
        }
    }
}

private fun io.ktor.server.application.ApplicationCall.memberId(): UserId =
    parameters["userId"]?.takeIf { it.isNotBlank() }?.let(::UserId) ?: throw BadRequestException("a user id is required")
