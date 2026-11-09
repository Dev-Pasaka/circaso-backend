package com.circaso.messaging

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.PostgresHarness.Companion.concurrently
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MemberAdded
import com.circaso.messaging.data.repositories.MessagingExpiry
import com.circaso.messaging.domain.models.CreateGroupCommand
import com.circaso.messaging.domain.models.Invite
import com.circaso.messaging.domain.models.InviteState
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Group invites on PostgreSQL (decision 2026-11-09): one open invite per person per group however the adds race, one
 * membership however the accepts race, invites going with their group, and the purge.
 */
class InviteDataTest {
    private val h = PostgresHarness()

    @BeforeTest
    fun clean() = TestPostgres.truncate("messaging")

    private fun inviteRows(chatId: ChatId, state: InviteState? = null): Int =
        if (state == null) {
            h.count("SELECT count(*) FROM messaging.invites WHERE chat_id = ?", chatId.value)
        } else {
            h.count("SELECT count(*) FROM messaging.invites WHERE chat_id = ? AND state = ?", chatId.value, state.name)
        }

    @Test
    fun `a create writes the group, its owner and one invite per person, and nobody else is a member`() =
        runBlocking<Unit> {
            val (amina, brian, chidi) = List(3) { UserId.new() }

            val chatId =
                h
                    .createGroup(CreateGroupCommand(amina, h.sealed, setOf(brian, chidi), UUID.randomUUID().toString()))
                    .data()
                    .chat.chatId

            assertEquals(1, h.count("SELECT count(*) FROM messaging.members WHERE chat_id = ?", chatId.value))
            assertEquals(2, inviteRows(chatId, InviteState.PENDING))
            assertEquals(1, h.chats.chat(chatId)!!.memberCount)
            assertEquals(listOf(chatId), h.invites.openOf(brian, h.clock.instant()).map { it.chatId })
        }

    @Test
    fun `adds of one person racing leave one open invite`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val chatId = h.group(amina, emptyList())

            val results = concurrently(6) { h.addMembers(amina, chatId, setOf(brian)) }

            assertTrue(results.all { it is Resource.Success })
            assertEquals(1, inviteRows(chatId, InviteState.PENDING))
        }

    @Test
    fun `accepts of one invite racing make one member and one MemberAdded`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val chatId = h.group(amina, emptyList())
            h.addMembers(amina, chatId, setOf(brian)).data()
            val inviteId =
                h.invites
                    .openFor(chatId, listOf(brian), h.clock.instant())
                    .single()
                    .inviteId

            val results = concurrently(6) { h.acceptInvite(brian, inviteId) }

            assertTrue(results.all { it is Resource.Success }, "a retry answers the group again")
            assertEquals(2, h.chats.chat(chatId)!!.memberCount)
            assertEquals(2, h.count("SELECT count(*) FROM messaging.members WHERE chat_id = ?", chatId.value))
            assertEquals(1, h.count("SELECT count(*) FROM messaging.outbox WHERE type = ?", MemberAdded.TYPE))
            assertEquals(1, inviteRows(chatId, InviteState.ACCEPTED))
        }

    @Test
    fun `a decline closes the row, and a second invite of the same person is a new row`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val chatId = h.group(amina, emptyList())
            h.addMembers(amina, chatId, setOf(brian)).data()
            val first =
                h.invites
                    .openFor(chatId, listOf(brian), h.clock.instant())
                    .single()
                    .inviteId

            h.declineInvite(brian, first).data()
            h.addMembers(amina, chatId, setOf(brian)).data()

            assertEquals(1, inviteRows(chatId, InviteState.DECLINED))
            assertEquals(1, inviteRows(chatId, InviteState.PENDING))
        }

    @Test
    fun `an expired pending invite gives way to a new one, and the purge takes run-out rows`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val chatId = h.group(amina, emptyList())
            val past =
                h.clock
                    .instant()
                    .minus(Duration.ofDays(31))
                    .truncatedTo(ChronoUnit.MILLIS)
            val stale =
                Invite(Uuid7.generate(), chatId, brian, amina, InviteState.PENDING, null, past, past.plusSeconds(60))
            h.invites.insert(listOf(stale), past)
            assertTrue(h.invites.openOf(brian, h.clock.instant()).isEmpty(), "run out, so not listed")

            h.addMembers(amina, chatId, setOf(brian)).data()

            assertEquals(1, inviteRows(chatId, InviteState.PENDING))
            assertEquals(1, inviteRows(chatId, InviteState.CANCELLED))
            MessagingExpiry(h.database).run()
            assertEquals(1, inviteRows(chatId), "the run-out row is purged; the open one stays")
        }

    @Test
    fun `a group's invites go with it`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val chatId = h.group(amina, emptyList())
            h.addMembers(amina, chatId, setOf(brian)).data()

            h.leaveGroup(amina, chatId).data()

            assertEquals(0, inviteRows(chatId))
            assertFalse(h.invites.openOf(brian, h.clock.instant()).any { it.chatId == chatId })
        }
}
