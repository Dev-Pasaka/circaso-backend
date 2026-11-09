package com.circaso.messaging

import com.circaso.messaging.api.InviteReceivedFrame
import com.circaso.messaging.api.InviteRemovedFrame
import com.circaso.messaging.api.InvitesChangedFrame
import com.circaso.messaging.api.MemberAdded
import com.circaso.messaging.api.MemberAddedFrame
import com.circaso.messaging.domain.models.Attachment
import com.circaso.messaging.domain.models.AttachmentKind
import com.circaso.messaging.domain.models.AttachmentState
import com.circaso.messaging.domain.models.AvatarChange
import com.circaso.messaging.domain.models.InviteKey
import com.circaso.messaging.domain.models.InviteState
import com.circaso.messaging.domain.usecases.GetAttachmentDownload
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group invites (decision 2026-11-09): adding someone invites them; they join by accepting, an invite they decline or
 * that runs out leaves nothing behind, and until they join they are in no member list, frame or event of the group.
 */
class InvitesTest {
    private val amina = UserId.new()
    private val brian = UserId.new()
    private val chidi = UserId.new()
    private val dalia = UserId.new()

    private val key: String = Base64.getEncoder().encodeToString(ByteArray(200) { 3 })

    /** Amina's group with Brian in it, and Chidi invited by her. */
    private suspend fun ChatHarness.invited(): com.circaso.messaging.api.ChatId {
        val chatId = group(amina, listOf(brian)).chat.chatId
        connect(amina, chidi)
        addMembers(amina, chatId, setOf(chidi), listOf(InviteKey(chidi, key))).data()
        return chatId
    }

    @Test
    fun `someone invited isn't a member until they accept, and is told and pushed once`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            h.connect(amina, chidi)
            h.frames.sent.clear()
            val events = h.outbox.written.size

            val added = h.addMembers(amina, chatId, setOf(chidi), listOf(InviteKey(chidi, key))).data()

            assertEquals(listOf(chidi), added.invited)
            assertFalse(added.members.any { it.profile.userId == chidi })
            assertFalse(h.directory.isMember(chatId, chidi), "not in wallets, chamas or calls either")
            assertEquals("messaging.chat_not_found", h.getChat(chidi, chatId).errorCode(), "no group to read")
            assertTrue(h.listChats(chidi).data().isEmpty())
            assertEquals(events, h.outbox.written.size, "no MemberAdded for an invite")
            val received =
                h.frames
                    .to(chidi)
                    .single()
                    .data<InviteReceivedFrame>()
            assertEquals(chatId, received.chatId)
            assertEquals(amina, received.byUserId)
            assertEquals("Person ${amina.toString().takeLast(4)}", received.byName)
            assertTrue(h.frames.to(brian).isEmpty(), "members hear of nobody until they join")
            assertEquals(listOf(InvitesChangedFrame(chatId)), h.frames.to(amina).map { it.frame })
        }

    @Test
    fun `my invites show the group's sealed name, photo, member count, inviter and my sealed key`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.invited()

            val mine = h.listMyInvites(chidi).data().single()

            assertEquals(chatId, mine.chat.chatId)
            assertEquals(h.sealed, mine.chat.sealedMeta)
            assertEquals(2, mine.chat.memberCount)
            assertEquals(amina, mine.invitedBy.userId)
            assertEquals(key, mine.invite.sealedKey)
            assertEquals(mine.invite.createdAt.plus(Duration.ofDays(30)), mine.invite.expiresAt)
            assertTrue(h.listMyInvites(brian).data().isEmpty())
        }

    @Test
    fun `accepting joins through the usual path, a seat, MemberAdded by the inviter, and X joined to everyone`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.invited()
            val inviteId = h.invites.openId(chatId, chidi)
            h.frames.sent.clear()

            val joined = h.acceptInvite(chidi, inviteId).data()

            assertEquals(3, joined.chat.memberCount)
            assertTrue(joined.members.any { it.profile.userId == chidi })
            assertTrue(h.directory.isMember(chatId, chidi))
            assertEquals(
                MemberAdded.TYPE,
                h.outbox.written
                    .last()
                    .type,
            )
            assertTrue(
                amina.toString() in
                    h.outbox.written
                        .last()
                        .data
                        .toString(),
                "byUserId is the inviter",
            )
            val added = h.frames.sent.filter { it.type == MemberAddedFrame.TYPE }
            assertEquals(setOf(amina, brian, chidi), added.map { it.to }.toSet())
            assertEquals(MemberAddedFrame(chatId, listOf(chidi), amina, joined = true), added.first().frame)
            assertTrue(h.listMyInvites(chidi).data().isEmpty(), "answered invites leave the inbox")
            assertTrue(h.listChatInvites(amina, chatId).data().isEmpty())
            assertEquals(
                InviteState.ACCEPTED,
                h.invites.invites
                    .getValue(inviteId)
                    .state,
            )

            val again = h.acceptInvite(chidi, inviteId).data()
            assertEquals(chatId, again.chat.chatId)
            assertEquals(
                3,
                h.chats.chats
                    .getValue(chatId)
                    .memberCount,
                "a retried accept joins nobody twice",
            )
        }

    @Test
    fun `declining closes the invite quietly, and a new invite can follow`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.invited()
            val inviteId = h.invites.openId(chatId, chidi)
            h.frames.sent.clear()

            h.declineInvite(chidi, inviteId).data()

            assertEquals(
                InviteState.DECLINED,
                h.invites.invites
                    .getValue(inviteId)
                    .state,
            )
            assertTrue(h.listMyInvites(chidi).data().isEmpty())
            assertTrue(h.listChatInvites(amina, chatId).data().isEmpty())
            assertEquals(listOf(InvitesChangedFrame.TYPE), h.frames.sent.map { it.type }, "only group info refreshes")
            assertEquals(listOf(amina), h.frames.sent.map { it.to })
            h.declineInvite(chidi, inviteId).data()
            assertEquals("messaging.invite_not_found", h.acceptInvite(chidi, inviteId).errorCode())
            assertNull(h.role(chatId, chidi))

            h.addMembers(amina, chatId, setOf(chidi)).data()
            val fresh = h.invites.openId(chatId, chidi)
            assertTrue(fresh != inviteId, "a new invite")
            h.acceptInvite(chidi, fresh).data()
            assertTrue(h.directory.isMember(chatId, chidi))
        }

    @Test
    fun `an invite runs out after 30 days, and inviting again makes a new one`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.invited()
            val inviteId = h.invites.openId(chatId, chidi)

            h.clock.advance(Duration.ofDays(30))

            assertTrue(h.listMyInvites(chidi).data().isEmpty())
            assertTrue(h.listChatInvites(amina, chatId).data().isEmpty())
            assertEquals("messaging.invite_not_found", h.acceptInvite(chidi, inviteId).errorCode())
            assertEquals("messaging.invite_not_found", h.declineInvite(chidi, inviteId).errorCode())
            assertNull(h.role(chatId, chidi))
            h.addMembers(amina, chatId, setOf(chidi)).data()
            assertEquals(1, h.listMyInvites(chidi).data().size)
            assertEquals(
                InviteState.CANCELLED,
                h.invites.invites
                    .getValue(inviteId)
                    .state,
                "the old one is closed",
            )
        }

    @Test
    fun `asking again while an invite is open keeps the one invite`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.invited()
            h.frames.sent.clear()

            val again = h.addMembers(amina, chatId, setOf(chidi)).data()

            assertEquals(listOf(chidi), again.invited)
            assertEquals(
                1,
                h.invites.invites.values
                    .count { it.userId == chidi },
            )
            assertTrue(h.frames.sent.isEmpty(), "nobody is told twice")
        }

    @Test
    fun `the inviter or an admin cancels an invite, another member can't`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.invited()
            val inviteId = h.invites.openId(chatId, chidi)

            assertEquals("messaging.not_admin", h.cancelInvite(brian, chatId, chidi).errorCode())
            assertEquals("messaging.chat_not_found", h.cancelInvite(dalia, chatId, chidi).errorCode())
            assertEquals("messaging.invite_not_found", h.cancelInvite(amina, chatId, dalia).errorCode())
            h.frames.sent.clear()

            h.cancelInvite(amina, chatId, chidi).data()

            assertEquals(
                InviteRemovedFrame(inviteId, chatId),
                h.frames
                    .to(chidi)
                    .single()
                    .frame,
            )
            assertEquals("messaging.invite_not_found", h.acceptInvite(chidi, inviteId).errorCode())
            assertTrue(h.listMyInvites(chidi).data().isEmpty())
        }

    @Test
    fun `a member who may add can cancel the invite they sent`() =
        runTest {
            val h = ChatHarness()
            val group = h.group(amina, listOf(brian))
            val chatId = group.chat.chatId
            h.update(amina, chatId, group.chat.version, membersCanAdd = true).data()
            h.connect(brian, dalia)
            h.addMembers(brian, chatId, setOf(dalia)).data()

            h.cancelInvite(brian, chatId, dalia).data()

            assertTrue(h.listChatInvites(amina, chatId).data().isEmpty())
        }

    @Test
    fun `group info lists the open invites to members only`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.invited()

            val pending = h.listChatInvites(brian, chatId).data().single()

            assertEquals(chidi, pending.user.userId)
            assertEquals(amina, pending.invitedBy.userId)
            assertEquals("messaging.chat_not_found", h.listChatInvites(chidi, chatId).errorCode())
        }

    @Test
    fun `nobody answers someone else's invite`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.invited()
            val inviteId = h.invites.openId(chatId, chidi)

            assertEquals("messaging.invite_not_found", h.acceptInvite(dalia, inviteId).errorCode())
            assertEquals("messaging.invite_not_found", h.declineInvite(brian, inviteId).errorCode())
            assertEquals("messaging.invite_not_found", h.acceptInvite(chidi, Uuid7.generate()).errorCode())
        }

    @Test
    fun `a sealed key must be base64 of at most 8 KB`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina).chat.chatId
            h.connect(amina, chidi)
            val big = Base64.getEncoder().encodeToString(ByteArray(8 * 1_024 + 1))

            assertEquals(
                "request.malformed",
                h.addMembers(amina, chatId, setOf(chidi), listOf(InviteKey(chidi, "not!"))).errorCode(),
            )
            assertEquals(
                "request.malformed",
                h.addMembers(amina, chatId, setOf(chidi), listOf(InviteKey(chidi, big))).errorCode(),
            )
            assertTrue(h.invites.invites.isEmpty())
        }

    @Test
    fun `someone invited may fetch the group's photo and nothing else of it`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.invited()
            val now = h.clock.instant()

            fun image() =
                Attachment(
                    Uuid7.generate(),
                    chatId,
                    amina,
                    AttachmentKind.IMAGE,
                    1_000,
                    AttachmentState.READY,
                    now,
                    null,
                )
            val photo = image().also { h.attachments.rows[it.attachmentId] = it }
            val other = image().also { h.attachments.rows[it.attachmentId] = it }
            h
                .update(
                    amina,
                    chatId,
                    h.chats.chats
                        .getValue(chatId)
                        .version,
                    avatar = AvatarChange.Set(photo.attachmentId),
                ).data()
            val download =
                GetAttachmentDownload(
                    h.attachments,
                    h.membership,
                    h.chats,
                    h.invites,
                    MemoryAttachmentStorage(),
                    h.config,
                    h.clock,
                )

            download(chidi, photo.attachmentId).data()
            assertEquals("messaging.attachment_not_found", download(chidi, other.attachmentId).errorCode())
            assertEquals("messaging.attachment_not_found", download(dalia, photo.attachmentId).errorCode())
        }
}
