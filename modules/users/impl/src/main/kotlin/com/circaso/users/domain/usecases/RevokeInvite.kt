package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.errors.UsersError
import com.circaso.users.domain.repositories.InviteRepository

/** U12: kills an invite. Only its owner can; anyone else's looks like one that doesn't exist. */
class RevokeInvite(private val invites: InviteRepository) {
    suspend operator fun invoke(userId: UserId, token: String): Resource<Unit> =
        if (invites.revoke(token, userId)) Resource.success(Unit) else Resource.error(UsersError.InviteInvalid)
}
