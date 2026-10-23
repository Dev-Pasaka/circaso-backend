package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.repositories.InviteRepository
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore

/** U12: kills an invite. Only its owner can; anyone else's looks like one that doesn't exist. */
class RevokeInvite(private val invites: InviteRepository, private val store: UsersStore) {
    suspend operator fun invoke(userId: UserId, token: String): Resource<Unit> {
        val invite = invites.find(token)?.takeIf { it.ownerId == userId } ?: return Resource.error(UsersError.InviteInvalid)
        store.commit(UsersChanges(revokedInvites = listOf(invite.token)))
        return Resource.success(Unit)
    }
}
