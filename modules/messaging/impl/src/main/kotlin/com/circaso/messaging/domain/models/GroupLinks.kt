package com.circaso.messaging.domain.models

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.shared.UserId
import java.time.Instant

/**
 * What a join link shows to anyone who has it (decision 2026-11-14): the group's name, description and a small photo,
 * published by an admin's phone for that link because the server can't read the group's sealed name. Never members,
 * messages or anything about money. [photo] is a small JPEG or PNG, null when the group has none.
 */
class LinkPreview(val name: String, val description: String?, val photo: ByteArray?) {
    override fun toString(): String = "LinkPreview(***)"
}

/**
 * A group's join link. One per group is live at a time: a reset or turning it off sets [revokedAt] and deletes the
 * published [preview] (null from then on), and the row stays a while as a tombstone. [createdBy] is the admin who made
 * it, who stands as the inviter of everyone who joins with it. [updatedAt] moves whenever the preview is republished.
 */
data class GroupLink(
    val token: GroupLinkToken,
    val chatId: ChatId,
    val createdBy: UserId,
    val preview: LinkPreview?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val revokedAt: Instant?,
) {
    val isLive: Boolean get() = revokedAt == null && preview != null
}

/** M29: the preview to publish, and whether to replace the link with a new one ([reset]: the old one stops working). */
class PublishLinkCommand(
    val caller: UserId,
    val chatId: ChatId,
    val preview: LinkPreview,
    val reset: Boolean,
)

/**
 * M31: a link as a signed-in person sees it before joining. [chatId] is set only when they are already in the group,
 * so their app opens the chat instead.
 */
data class LinkLookup(val link: GroupLink, val memberCount: Int, val chatId: ChatId?)

/**
 * A join decided under the chat's lock: the chat as it now is, the link used, the `MemberAdded` written for the
 * person (null when they were already in), and the open invite of theirs that the join answered, if any.
 */
data class LinkJoined(
    val chat: Chat,
    val link: GroupLink,
    val event: com.circaso.shared.EventEnvelope?,
    val answeredInvite: Invite?,
)
