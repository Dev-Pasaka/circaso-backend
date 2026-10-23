package com.circaso.users.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.models.Invite
import com.circaso.users.domain.models.InviteLink
import com.circaso.users.domain.repositories.UsersChanges
import com.circaso.users.domain.repositories.UsersStore
import com.circaso.users.domain.support.UsersConfig
import com.circaso.users.domain.support.randomInviteToken
import java.time.Clock

/** U12: a shareable link and QR payload that stand in for giving out a number. Expires after 7 days and can be revoked. */
class CreateInvite(private val store: UsersStore, private val config: UsersConfig, private val clock: Clock) {
    suspend operator fun invoke(userId: UserId): Resource<InviteLink> {
        val now = clock.instant()
        val token = randomInviteToken()
        store.commit(UsersChanges(invites = listOf(Invite(token, userId, "LINK", now))))
        return Resource.success(InviteLink(token, config.inviteBaseUrl + token, "circaso://i/$token", now.plus(config.inviteTtl)))
    }
}
