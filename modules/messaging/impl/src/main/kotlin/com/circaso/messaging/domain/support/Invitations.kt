package com.circaso.messaging.domain.support

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.Invite
import com.circaso.messaging.domain.models.InviteKey
import com.circaso.messaging.domain.models.InviteState
import com.circaso.messaging.domain.models.InvitesSent
import com.circaso.messaging.domain.repositories.InviteRepository
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.users.api.Profiles
import java.time.Clock
import java.util.Base64

/** Named in an invite frame when users can't give the inviter's profile (a deleted account), as [ChatViews] does. */
private const val FALLBACK_INVITER_NAME = "Someone"

/**
 * Sending group invites (decision 2026-11-09), shared by M5 and M9: adding someone to a group invites them, and they
 * become a member only by accepting (`AcceptInvite`). [write] runs inside the caller's transaction, after the chat's
 * row is locked, so two adds of one person at once leave one open invite; [announce] runs after the commit.
 */
class Invitations(
    private val invites: InviteRepository,
    private val membership: Membership,
    private val notifier: ChatNotifier,
    private val profiles: Profiles,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    /**
     * The sealed keys by person, checked for shape: base64 of at most `maxInviteKeyBytes`. Keys for people not being
     * invited are simply never used. A bad one answers `request.malformed`, since only a broken phone sends it.
     */
    fun keysOf(keys: List<InviteKey>): Resource<Map<UserId, String>> {
        for (key in keys) {
            val bytes = runCatching { Base64.getDecoder().decode(key.sealedKey) }.getOrNull()
            if (bytes == null || bytes.isEmpty() || bytes.size > config.maxInviteKeyBytes) {
                return Resource.error(SharedError.Malformed("inviteKeys", "each must be base64 of at most 8 KB"))
            }
        }
        return Resource.success(keys.associate { it.userId to it.sealedKey })
    }

    /**
     * Invites each of [people] to [chat] from [by], skipping anyone whose invite is still open (they keep it, sealed
     * key and expiry as they were). Inside a `Transactor` block, after the chat's lock.
     */
    suspend fun write(
        chat: Chat,
        by: UserId,
        people: Set<UserId>,
        keys: Map<UserId, String>,
    ): InvitesSent {
        if (people.isEmpty()) return InvitesSent(chat, emptyList(), emptySet())
        val now = clock.instant()
        val open = invites.openFor(chat.chatId, people, now).mapTo(HashSet()) { it.userId }
        val fresh =
            (people - open).sortedBy { it.toString() }.map {
                Invite(
                    inviteId = Uuid7.generate(),
                    chatId = chat.chatId,
                    userId = it,
                    invitedBy = by,
                    state = InviteState.PENDING,
                    sealedKey = keys[it],
                    createdAt = now,
                    expiresAt = now.plus(config.inviteTtl),
                )
            }
        invites.insert(fresh, now)
        return InvitesSent(chat, fresh, open)
    }

    /** After the commit: each new invite to its person (pushed), and the group's admins and inviter told. */
    suspend fun announce(sent: InvitesSent, by: UserId) {
        if (sent.invites.isEmpty()) return
        val name = profiles.get(setOf(by))[by]?.displayName?.takeIf { it.isNotBlank() } ?: FALLBACK_INVITER_NAME
        for (invite in sent.invites) notifier.inviteReceived(invite, name)
        changed(sent.chat.chatId, by)
    }

    /** `invites.changed` to the group's admins and [inviter], whose group info lists the open invites. */
    suspend fun changed(chatId: ChatId, inviter: UserId) {
        notifier.invitesChanged(membership.admins(chatId) + inviter, chatId)
    }
}
