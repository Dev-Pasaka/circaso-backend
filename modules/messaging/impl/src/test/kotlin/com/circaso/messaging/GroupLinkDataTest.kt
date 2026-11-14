package com.circaso.messaging

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.messaging.data.repositories.MessagingExpiry
import com.circaso.messaging.domain.models.LinkPreview
import com.circaso.messaging.domain.models.PublishLinkCommand
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.UserId
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.time.Duration
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Group join links on PostgreSQL (decision 2026-11-14): the published preview as it is stored and deleted, one live
 * link per group however the admins race, one membership however the joins race, the removals that keep someone out,
 * rows going with their group, and the purge.
 */
class GroupLinkDataTest {
    private val h = PostgresHarness()
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 1, 2, -1, -128)

    private companion object {
        const val COUNT_LINKS = "SELECT count(*) FROM messaging.group_links WHERE chat_id = ?"
        const val COUNT_MEMBER = "SELECT count(*) FROM messaging.members WHERE chat_id = ? AND user_id = ?"
    }

    @BeforeTest
    fun clean() = TestPostgres.truncate("messaging")

    private fun linkRows(chatId: ChatId, live: Boolean? = null): Int =
        when (live) {
            null -> h.count(COUNT_LINKS, chatId.value)
            true -> h.count("$COUNT_LINKS AND revoked_at IS NULL", chatId.value)
            false -> h.count("$COUNT_LINKS AND revoked_at IS NOT NULL", chatId.value)
        }

    private suspend fun publish(
        caller: UserId,
        chatId: ChatId,
        reset: Boolean = false,
        name: String = "Book club",
    ) = h.publishGroupLink(PublishLinkCommand(caller, chatId, LinkPreview(name, "Fridays at six.", jpeg), reset))

    @Test
    fun `a link is stored with its preview and read back by token and by group`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val chatId = h.group(amina, emptyList())

            val link = publish(amina, chatId).data()

            val byToken = h.links.find(link.token)!!
            assertEquals(chatId, byToken.chatId)
            assertEquals(amina, byToken.createdBy)
            assertEquals("Book club", byToken.preview?.name)
            assertEquals("Fridays at six.", byToken.preview?.description)
            assertContentEquals(jpeg, byToken.preview?.photo)
            assertTrue(byToken.isLive)
            assertEquals(link.token, h.links.liveOf(chatId)?.token)
            assertNull(h.links.find(GroupLinkToken.new()))
        }

    @Test
    fun `turning a link off deletes the published copy in its row and leaves a tombstone for the purge`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val chatId = h.group(amina, emptyList())
            val link = publish(amina, chatId).data()

            h.turnOffGroupLink(amina, chatId).data()

            assertNull(h.links.liveOf(chatId))
            val gone = h.links.find(link.token)!!
            assertNull(gone.preview)
            assertNotNull(gone.revokedAt)
            assertEquals(
                1,
                h.count(
                    "SELECT count(*) FROM messaging.group_links WHERE token = ? AND name IS NULL " +
                        "AND description IS NULL AND photo IS NULL AND expires_at IS NOT NULL",
                    link.token.value,
                ),
            )
            MessagingExpiry(h.database).run()
            assertEquals(1, linkRows(chatId), "the tombstone stays its 30 days")
        }

    @Test
    fun `the purge takes tombstones past their time and never a live link`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val chatId = h.group(amina, emptyList())
            publish(amina, chatId).data()
            val past = h.clock.instant().minus(Duration.ofDays(40))
            h.links.revoke(chatId, past, past.plus(Duration.ofDays(30)))
            val live = publish(amina, chatId).data()

            MessagingExpiry(h.database).run()

            assertEquals(1, linkRows(chatId))
            assertEquals(live.token, h.links.liveOf(chatId)?.token)
        }

    @Test
    fun `admins making and resetting the link at once leave one live link`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val chatId = h.group(amina, emptyList())

            val made = coroutineScope { List(8) { async { publish(amina, chatId, reset = it % 2 == 0) } }.awaitAll() }

            assertTrue(made.all { it is com.circaso.shared.Resource.Success })
            assertEquals(1, linkRows(chatId, live = true))
            assertEquals(linkRows(chatId) - 1, linkRows(chatId, live = false))
        }

    @Test
    fun `joins of one person racing make one membership and one event`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val chatId = h.group(amina, emptyList())
            val link = publish(amina, chatId).data()

            val joined = coroutineScope { List(8) { async { h.joinByGroupLink(brian, link.token) } }.awaitAll() }

            assertTrue(joined.all { it is com.circaso.shared.Resource.Success })
            assertEquals(1, h.count(COUNT_MEMBER, chatId.value, brian.value))
            assertEquals(2, h.chats.chat(chatId)!!.memberCount)
        }

    @Test
    fun `joins racing for the last places never pass the limit`() =
        runBlocking<Unit> {
            val small = PostgresHarness(MessagingConfig(maxGroupMembers = 4))
            val amina = UserId.new()
            val chatId = small.group(amina, emptyList())
            val link =
                small
                    .publishGroupLink(PublishLinkCommand(amina, chatId, LinkPreview("Small", null, null), false))
                    .data()
            val people = List(10) { UserId.new() }

            val results = coroutineScope { people.map { async { small.joinByGroupLink(it, link.token) } }.awaitAll() }

            assertEquals(3, results.count { it is com.circaso.shared.Resource.Success })
            assertTrue(
                results
                    .filterIsInstance<com.circaso.shared.Resource.Error>()
                    .all { it.error.code == "messaging.group_full" },
            )
            assertEquals(4, small.chats.chat(chatId)!!.memberCount)
            assertEquals(4, small.count("SELECT count(*) FROM messaging.members WHERE chat_id = ?", chatId.value))
        }

    @Test
    fun `an admin's removal is recorded with the removal, a leave isn't, and an accepted invite clears it`() =
        runBlocking<Unit> {
            val (amina, brian, chidi) = List(3) { UserId.new() }
            val chatId = h.group(amina, listOf(brian, chidi))
            val link = publish(amina, chatId).data()

            h.removeMember(amina, chatId, brian).data()
            h.leaveGroup(chidi, chatId).data()

            assertTrue(h.links.wasRemoved(chatId, brian))
            assertFalse(h.links.wasRemoved(chatId, chidi))
            assertEquals("messaging.link_not_allowed", h.joinByGroupLink(brian, link.token).errorCode())
            h.joinByGroupLink(chidi, link.token).data()

            h.addMembers(amina, chatId, setOf(brian)).data()
            val invite = h.invites.openOf(brian, h.clock.instant()).single { it.chatId == chatId }
            h.acceptInvite(brian, invite.inviteId).data()
            assertFalse(h.links.wasRemoved(chatId, brian))
        }

    @Test
    fun `a group's link and removals go with it`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val chatId = h.group(amina, listOf(brian))
            val link = publish(amina, chatId).data()
            h.removeMember(amina, chatId, brian).data()

            h.leaveGroup(amina, chatId).data()

            assertEquals(0, linkRows(chatId))
            assertNull(h.links.find(link.token))
            assertEquals(0, h.count("SELECT count(*) FROM messaging.group_removals WHERE chat_id = ?", chatId.value))
        }
}
