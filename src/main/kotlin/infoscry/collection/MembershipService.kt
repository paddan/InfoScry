package infoscry.collection

import infoscry.account.CollectionRole
import infoscry.account.UserId
import infoscry.domain.ApiFaultException
import infoscry.domain.ApiStatus
import infoscry.domain.CollectionId
import infoscry.storage.AccountStore
import infoscry.storage.Instants
import infoscry.storage.MembershipRow
import infoscry.storage.MembershipStore
import org.slf4j.LoggerFactory

/** The change would leave the collection without an owner. */
class LastOwnerException : ApiFaultException(
    ApiStatus.CONFLICT,
    "LAST_OWNER",
    "a collection needs at least one owner; make someone else an owner first",
)

/**
 * Who is a member of a collection, and with which role.
 *
 * Every change is safe to repeat: giving a member the role they have, or removing someone who is not a
 * member, changes nothing. A collection always keeps one owner; the check locks the owner rows, so two
 * owners demoting each other at once cannot both succeed.
 */
class MembershipService(private val store: MembershipStore, private val accounts: AccountStore) {

    fun members(collectionId: CollectionId): List<MembershipRow> = store.list(collectionId)

    fun roleOf(collectionId: CollectionId, userId: UserId): CollectionRole? = store.roleOf(collectionId, userId)

    fun rolesOf(userId: UserId): Map<CollectionId, CollectionRole> = store.rolesOf(userId)

    /** Adds [userId] with [role], or changes their role. [actor] is the owner or administrator doing it. */
    fun put(collectionId: CollectionId, userId: UserId, role: CollectionRole, actor: UserId): MembershipRow =
        store.transaction {
            val user = accounts.get(userId)?.user ?: throw NoSuchElementException("no user with id ${userId.value}")
            require(user.active) { "${user.username} is deactivated and cannot be added to a collection" }
            val current = store.roleOf(collectionId, userId)
            if (current != role) {
                if (current == CollectionRole.OWNER && store.lockOwners(collectionId).none { it != userId }) {
                    throw LastOwnerException()
                }
                store.put(collectionId, userId, role, Instants.now(), actor)
                LOGGER.atInfo().addKeyValue("collection_id", collectionId.value).addKeyValue("username", user.username)
                    .addKeyValue("role", role.name).log("membership set")
            }
            store.list(collectionId).single { it.userId == userId }
        }

    /** Removes [userId]; removing someone who is not a member succeeds. */
    fun remove(collectionId: CollectionId, userId: UserId) {
        store.transaction {
            val current = store.roleOf(collectionId, userId) ?: return@transaction
            if (current == CollectionRole.OWNER && store.lockOwners(collectionId).none { it != userId }) {
                throw LastOwnerException()
            }
            store.remove(collectionId, userId)
            LOGGER.atInfo().addKeyValue("collection_id", collectionId.value).addKeyValue("user_id", userId.value)
                .log("membership removed")
        }
    }

    private companion object {
        val LOGGER = LoggerFactory.getLogger("infoscry.memberships")
    }
}
