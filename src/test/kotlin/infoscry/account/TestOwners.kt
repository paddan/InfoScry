package infoscry.account

import infoscry.storage.AccountStore
import infoscry.storage.Database

/** A user to own what a store test creates, now that conversations and memberships belong to someone. */
fun testOwner(database: Database, username: String = "owner"): User =
    AccountService(AccountStore(database), passwords = Passwords(memoryKiB = 64, iterations = 1))
        .createUser(username, null, UserRole.USER, actor = null).user
