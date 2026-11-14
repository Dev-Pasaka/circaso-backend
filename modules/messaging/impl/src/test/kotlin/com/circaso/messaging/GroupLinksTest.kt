package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupLinkChangedFrame
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.messaging.api.InviteRemovedFrame
import com.circaso.messaging.api.MemberAdded
import com.circaso.messaging.api.MemberAddedFrame
import com.circaso.messaging.domain.models.GroupLink
import com.circaso.messaging.domain.models.LinkPreview
import com.circaso.messaging.domain.models.PublishLinkCommand
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.UserId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group join links (decision 2026-11-14): an admin makes one link per group and publishes its preview; anyone with it
 * joins as an ordinary member, by the same path as an accepted invite; a reset or turned-off link stops working and
 * its published copy is gone; and a link never lets in someone an admin removed, someone blocked by or blocking an
 * admin, or a support account.
 */
class GroupLinksTest {
    private val amina = UserId.new()
    private val brian = UserId.new()
    private val chidi = UserId.new()
    private val dalia = UserId.new()

    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 1, 2, 3)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 9, 9)

    private fun preview(
        name: String = "Book club",
        description: String? = "Fridays at six.",
        photo: ByteArray? = jpeg,
    ) = LinkPreview(name, description, photo)

    private suspend fun ChatHarness.publish(
        caller: UserId,
        chatId: ChatId,
        preview: LinkPreview = preview(),
        reset: Boolean = false,
    ) = publishGroupLink(PublishLinkCommand(caller, chatId, preview, reset))

    /** Amina's group with Brian in it, and its link. */
    private suspend fun ChatHarness.linked(): Pair<ChatId, GroupLink> {
        val chatId = group(amina, listOf(brian)).chat.chatId
        return chatId to publish(amina, chatId).data()
    }

    // ---- making, republishing, resetting, turning off ----

    @Test
    fun `an admin makes the link with a 131-bit token and the published preview, and admins are told`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian)).chat.chatId
            h.frames.sent.clear()

            val link = h.publish(amina, chatId).data()

            assertEquals(GroupLinkToken.LENGTH, link.token.value.length)
            assertNotNull(GroupLinkToken.parse(link.token.value))
            assertEquals(amina, link.createdBy)
            assertEquals("Book club", link.preview?.name)
            assertEquals("Fridays at six.", link.preview?.description)
            assertContentEquals(jpeg, link.preview?.photo)
            assertEquals(link, h.getGroupLink(amina, chatId).value())
            assertEquals(listOf(GroupLinkChangedFrame(chatId)), h.frames.to(amina).map { it.frame })
            assertTrue(h.frames.to(brian).isEmpty(), "members aren't told about the link")
        }

    @Test
    fun `tokens are base62, 22 long and never repeat`() {
        val tokens = List(500) { GroupLinkToken.new().value }
        assertEquals(500, tokens.toSet().size)
        assertTrue(tokens.all { Regex("[0-9A-Za-z]{22}").matches(it) })
        assertNull(GroupLinkToken.parse("abcDEF123456"), "a share code (12) is not a join link")
        assertNull(GroupLinkToken.parse("a".repeat(21) + "-"))
        assertEquals("GroupLinkToken(***)", GroupLinkToken.new().toString(), "never logged")
    }

    @Test
    fun `only admins see, make or turn off the link, and outsiders learn nothing`() =
        runTest {
            val h = ChatHarness()
            val (chatId, _) = h.linked()

            assertEquals("messaging.not_admin", h.getGroupLink(brian, chatId).errorCode())
            assertEquals("messaging.not_admin", h.publish(brian, chatId).errorCode())
            assertEquals("messaging.not_admin", h.turnOffGroupLink(brian, chatId).errorCode())
            assertEquals("messaging.chat_not_found", h.getGroupLink(chidi, chatId).errorCode())
            assertEquals("messaging.chat_not_found", h.publish(chidi, chatId).errorCode())
            assertEquals("messaging.chat_not_found", h.turnOffGroupLink(chidi, chatId).errorCode())
            assertNotNull(h.links.liveOf(chatId), "still on")
        }

    @Test
    fun `a member made admin can use the link, and a direct chat has none`() =
        runTest {
            val h = ChatHarness()
            val (chatId, link) = h.linked()
            h.setRole(amina, chatId, brian, Role.ADMIN).data()

            assertEquals(link.token, h.getGroupLink(brian, chatId).value()?.token)

            h.connect(amina, chidi)
            val direct =
                h
                    .openDirectChat(amina, chidi)
                    .data()
                    .chat.chatId
            assertEquals("messaging.not_a_group", h.publish(amina, direct).errorCode())
            assertEquals("messaging.not_a_group", h.getGroupLink(amina, direct).errorCode())
            assertEquals("messaging.not_a_group", h.turnOffGroupLink(amina, direct).errorCode())
        }

    @Test
    fun `publishing again replaces the preview and keeps the link`() =
        runTest {
            val h = ChatHarness()
            val (chatId, link) = h.linked()
            h.setRole(amina, chatId, brian, Role.ADMIN).data()
            h.clock.advance()

            val again = h.publish(brian, chatId, preview("Readers", description = null, photo = null)).data()

            assertEquals(link.token, again.token, "links already shared keep working")
            assertEquals(amina, again.createdBy, "the maker stays the inviter")
            assertEquals("Readers", again.preview?.name)
            assertNull(again.preview?.description)
            assertNull(again.preview?.photo)
            assertTrue(again.updatedAt.isAfter(link.updatedAt))
            val page = h.readGroupLinkPage(link.token).value()
            assertEquals("Readers", page?.name)
            assertFalse(page!!.hasPhoto)
            assertNull(h.readGroupLinkPhoto(link.token).value())
        }

    @Test
    fun `a reset makes a new link, and the old one stops working with its preview deleted`() =
        runTest {
            val h = ChatHarness()
            val (chatId, old) = h.linked()

            val fresh = h.publish(amina, chatId, reset = true).data()

            assertNotEquals(old.token, fresh.token)
            assertEquals(fresh.token, h.getGroupLink(amina, chatId).value()?.token)
            val tombstone = h.links.links.getValue(old.token)
            assertNull(tombstone.preview, "nothing of the group stays behind")
            assertNotNull(tombstone.revokedAt)
            assertNull(h.readGroupLinkPage(old.token).value())
            assertNull(h.readGroupLinkPhoto(old.token).value())
            assertEquals("messaging.link_not_found", h.lookUpGroupLink(chidi, old.token).errorCode())
            assertEquals("messaging.link_not_found", h.joinByGroupLink(chidi, old.token).errorCode())
            assertEquals(
                chatId,
                h
                    .joinByGroupLink(chidi, fresh.token)
                    .data()
                    .chat.chatId,
            )
        }

    @Test
    fun `turning it off deletes the published copy, keeps the members, and is safe to repeat`() =
        runTest {
            val h = ChatHarness()
            val (chatId, link) = h.linked()
            h.joinByGroupLink(chidi, link.token).data()
            h.frames.sent.clear()

            h.turnOffGroupLink(amina, chatId).data()
            h.turnOffGroupLink(amina, chatId).data()

            assertNull(h.getGroupLink(amina, chatId).value())
            assertNull(
                h.links.links
                    .getValue(link.token)
                    .preview,
            )
            assertNull(h.readGroupLinkPage(link.token).value())
            assertEquals("messaging.link_not_found", h.joinByGroupLink(dalia, link.token).errorCode())
            assertTrue(h.directory.isMember(chatId, chidi), "whoever joined stays")
            assertEquals(1, h.frames.to(amina).count { it.frame is GroupLinkChangedFrame }, "told once")

            val back = h.publish(amina, chatId).data()
            assertNotEquals(link.token, back.token, "turning it on again is a new link")
        }

    @Test
    fun `a preview needs a name, at most 500 characters of description and a small JPEG or PNG`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina).chat.chatId
            val bad =
                listOf(
                    preview(name = "  "),
                    preview(name = "n".repeat(101)),
                    preview(description = "d".repeat(501)),
                    preview(photo = byteArrayOf(1, 2, 3, 4, 5)),
                    preview(photo = "GIF89a....".toByteArray()),
                    preview(photo = jpeg + ByteArray(MessagingConfig().maxLinkPhotoBytes)),
                )
            for (preview in bad) assertEquals("messaging.link_invalid", h.publish(amina, chatId, preview).errorCode())
            assertNull(h.links.liveOf(chatId))

            h
                .publish(
                    amina,
                    chatId,
                    preview(name = "n".repeat(100), description = "😀".repeat(500), photo = png),
                ).data()
            val token = h.links.liveOf(chatId)!!.token
            assertEquals("image/png", h.readGroupLinkPhoto(token).value()?.contentType)
        }

    // ---- the preview ----

    @Test
    fun `the page shows the published preview and the member count, nothing else`() =
        runTest {
            val h = ChatHarness()
            val (_, link) = h.linked()

            val page = h.readGroupLinkPage(link.token).value()!!

            assertEquals("Book club", page.name)
            assertEquals("Fridays at six.", page.description)
            assertTrue(page.hasPhoto)
            assertEquals(2, page.memberCount)
            assertEquals(link.updatedAt.toEpochMilli(), page.photoVersion)
            val photo = h.readGroupLinkPhoto(link.token).value()!!
            assertEquals("image/jpeg", photo.contentType)
            assertContentEquals(jpeg, photo.bytes)
            assertNull(h.readGroupLinkPage(GroupLinkToken.new()).value(), "an unknown token shows nothing")
        }

    @Test
    fun `a lookup gives the preview, and the chat only to someone already in it`() =
        runTest {
            val h = ChatHarness()
            val (chatId, link) = h.linked()

            val outsider = h.lookUpGroupLink(chidi, link.token).data()
            assertEquals("Book club", outsider.link.preview?.name)
            assertEquals(2, outsider.memberCount)
            assertNull(outsider.chatId, "an outsider never learns the chat's id")
            assertEquals(chatId, h.lookUpGroupLink(brian, link.token).data().chatId)
            assertEquals("messaging.link_not_found", h.lookUpGroupLink(chidi, GroupLinkToken.new()).errorCode())
        }

    @Test
    fun `lookups are limited to 60 an hour per person`() =
        runTest {
            val h = ChatHarness()
            val (_, link) = h.linked()
            repeat(60) { h.lookUpGroupLink(chidi, link.token).data() }

            val refused = h.lookUpGroupLink(chidi, link.token)

            assertEquals("messaging.rate_limited", refused.errorCode())
            assertEquals(
                MemoryLinkRateLimit.RETRY_AFTER,
                (refused as com.circaso.shared.Resource.Error).error.details?.retryAfterSeconds,
            )
            h.lookUpGroupLink(dalia, link.token).data()
        }

    // ---- joining ----

    @Test
    fun `joining makes them a member at once, by the same path as an accepted invite`() =
        runTest {
            val h = ChatHarness()
            val (chatId, link) = h.linked()
            h.frames.sent.clear()
            val events = h.outbox.written.size

            val joined = h.joinByGroupLink(chidi, link.token).data()

            assertEquals(chatId, joined.chat.chatId)
            assertEquals(3, joined.chat.memberCount)
            assertEquals(Role.MEMBER, h.role(chatId, chidi), "no more rights than any member")
            assertTrue(h.directory.isMember(chatId, chidi), "wallets, chamas and calls see a member")
            assertFalse(chidi in h.directory.admins(chatId))
            assertEquals(amina, h.directory.owner(chatId), "the owner is who it was")
            val event =
                h.outbox.written
                    .drop(events)
                    .single()
            assertEquals(MemberAdded.TYPE, event.type)
            assertTrue(h.bus.published.any { it.eventId == event.eventId })
            for (person in listOf(amina, brian, chidi)) {
                val frame =
                    h.frames
                        .to(person)
                        .single { it.frame is MemberAddedFrame }
                        .data<MemberAddedFrame>()
                assertEquals(MemberAddedFrame(chatId, listOf(chidi), amina, joined = true, viaLink = true), frame)
            }
            assertEquals(
                chatId,
                h
                    .getChat(chidi, chatId)
                    .data()
                    .chat.chatId,
            )
        }

    @Test
    fun `someone already in the group gets the group again and nothing is written`() =
        runTest {
            val h = ChatHarness()
            val (chatId, link) = h.linked()
            h.joinByGroupLink(chidi, link.token).data()
            h.frames.sent.clear()
            val events = h.outbox.written.size

            val again = h.joinByGroupLink(chidi, link.token).data()
            val member = h.joinByGroupLink(brian, link.token).data()

            assertEquals(chatId, again.chat.chatId)
            assertEquals(3, again.chat.memberCount)
            assertEquals(3, member.chat.memberCount)
            assertEquals(events, h.outbox.written.size)
            assertTrue(h.frames.sent.isEmpty())
        }

    @Test
    fun `a full group says so and nobody is added`() =
        runTest {
            val h = ChatHarness(MessagingConfig(maxGroupMembers = 2))
            val (chatId, link) = h.linked()

            assertEquals("messaging.group_full", h.joinByGroupLink(chidi, link.token).errorCode())
            assertFalse(h.directory.isMember(chatId, chidi))
            assertEquals(
                2,
                h.chats.chats
                    .getValue(chatId)
                    .memberCount,
            )

            h.leaveGroup(brian, chatId).data()
            h.joinByGroupLink(chidi, link.token).data()
        }

    @Test
    fun `someone an admin removed can't come back by link until an admin invites them`() =
        runTest {
            val h = ChatHarness()
            val (chatId, link) = h.linked()
            h.joinByGroupLink(chidi, link.token).data()
            h.removeMember(amina, chatId, chidi).data()

            assertEquals("messaging.link_not_allowed", h.joinByGroupLink(chidi, link.token).errorCode())
            val fresh = h.publish(amina, chatId, reset = true).data()
            assertEquals(
                "messaging.link_not_allowed",
                h.joinByGroupLink(chidi, fresh.token).errorCode(),
                "nor a new link",
            )
            assertFalse(h.directory.isMember(chatId, chidi))

            h.connect(amina, chidi)
            h.addAndJoin(amina, chatId, setOf(chidi))
            h.leaveGroup(chidi, chatId).data()
            h.joinByGroupLink(chidi, fresh.token).data()
            assertTrue(h.directory.isMember(chatId, chidi), "accepting the admin's invite cleared the removal")
        }

    @Test
    fun `someone who left by themselves can join again, and a removed person's open invite lets them in`() =
        runTest {
            val h = ChatHarness()
            val (chatId, link) = h.linked()
            h.leaveGroup(brian, chatId).data()
            h.joinByGroupLink(brian, link.token).data()
            assertTrue(h.directory.isMember(chatId, brian))

            h.removeMember(amina, chatId, brian).data()
            h.connect(amina, brian)
            h.addMembers(amina, chatId, setOf(brian)).data()
            h.frames.sent.clear()

            h.joinByGroupLink(brian, link.token).data()

            assertTrue(h.directory.isMember(chatId, brian))
            assertTrue(h.invites.openOf(brian, h.clock.instant()).isEmpty(), "the join answered the invite")
            assertTrue(h.frames.to(brian).any { it.frame is InviteRemovedFrame })
            assertFalse(h.links.wasRemoved(chatId, brian))
        }

    @Test
    fun `a link doesn't bypass a block with the link's maker or any admin, either way`() =
        runTest {
            val h = ChatHarness()
            val (chatId, link) = h.linked()
            h.setRole(amina, chatId, brian, Role.ADMIN).data()

            h.reach.blocks += amina to chidi
            assertEquals("messaging.link_not_allowed", h.joinByGroupLink(chidi, link.token).errorCode())
            h.reach.blocks.clear()

            h.reach.blocks += chidi to brian
            assertEquals("messaging.link_not_allowed", h.joinByGroupLink(chidi, link.token).errorCode())
            h.reach.blocks.clear()

            assertFalse(h.directory.isMember(chatId, chidi))
            h.joinByGroupLink(chidi, link.token).data()
        }

    @Test
    fun `a block with the maker still counts after they left, and a block with an ordinary member doesn't`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina, listOf(brian, dalia)).chat.chatId
            h.setRole(amina, chatId, brian, Role.ADMIN).data()
            val link = h.publish(brian, chatId).data()
            h.leaveGroup(brian, chatId).data()

            h.reach.blocks += dalia to chidi
            h.joinByGroupLink(chidi, link.token).data()

            val erin = UserId.new()
            h.reach.blocks += brian to erin
            assertEquals("messaging.link_not_allowed", h.joinByGroupLink(erin, link.token).errorCode())
        }

    @Test
    fun `a support account can't join`() =
        runTest {
            val h = ChatHarness()
            val (chatId, link) = h.linked()
            h.profiles.support += chidi

            assertEquals("messaging.link_not_allowed", h.joinByGroupLink(chidi, link.token).errorCode())
            assertFalse(h.directory.isMember(chatId, chidi))
        }

    @Test
    fun `joins are limited to 20 an hour per person`() =
        runTest {
            val h = ChatHarness()
            val (_, link) = h.linked()
            repeat(20) { h.joinByGroupLink(chidi, GroupLinkToken.new()) }

            assertEquals("messaging.rate_limited", h.joinByGroupLink(chidi, link.token).errorCode())
            h.joinByGroupLink(dalia, link.token).data()
        }

    @Test
    fun `a group with money gives a link joiner nothing a member doesn't have`() =
        runTest {
            // Wallet and chama decide by messaging's directory: the owner alone moves a group's money (decision
            // 2026-11-06), and who the owner and admins are doesn't change when someone joins by link.
            val h = ChatHarness()
            val (chatId, link) = h.linked()
            val ownerBefore = h.directory.owner(chatId)
            val adminsBefore = h.directory.admins(chatId)

            h.joinByGroupLink(chidi, link.token).data()

            assertEquals(ownerBefore, h.directory.owner(chatId))
            assertEquals(adminsBefore, h.directory.admins(chatId))
            assertEquals(Role.MEMBER, h.role(chatId, chidi))
            assertEquals("messaging.not_admin", h.publish(chidi, chatId).errorCode(), "nor the link itself")
            assertEquals("messaging.not_admin", h.removeMember(chidi, chatId, brian).errorCode())
        }

    @Test
    fun `when the group is gone, so is its link`() =
        runTest {
            val h = ChatHarness()
            val chatId = h.group(amina).chat.chatId
            val link = h.publish(amina, chatId).data()

            h.leaveGroup(amina, chatId).data()

            assertNull(h.readGroupLinkPage(link.token).value())
            assertEquals("messaging.link_not_found", h.joinByGroupLink(chidi, link.token).errorCode())
        }
}

/** A success whose answer may be "none" (no link, no page, no photo); an error fails the test. */
private fun <T> com.circaso.shared.Resource<T?>.value(): T? =
    (this as? com.circaso.shared.Resource.Success)?.data.also {
        check(this is com.circaso.shared.Resource.Success) { "expected success, got $this" }
    }
