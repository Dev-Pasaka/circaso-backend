package com.circaso.messaging

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.PostgresHarness.Companion.RACE_RUNS
import com.circaso.messaging.PostgresHarness.Companion.concurrently
import com.circaso.messaging.PostgresHarness.Companion.lowestUuid7
import com.circaso.messaging.api.ChatCreated
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupDeleted
import com.circaso.messaging.api.MemberAdded
import com.circaso.messaging.api.MemberRemoved
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.data.repositories.MessagingExpiry
import com.circaso.messaging.data.repositories.MessagingOutboxImpl
import com.circaso.messaging.domain.models.ChatListing
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.repositories.MessagingOutbox
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.EventEnvelope
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chats and membership on PostgreSQL: the pair claim, the member cap, removals and successors, each settled by the
 * chat row's lock and the returning forms, and each change landing with its outbox row or not at all.
 */
class ChatDataTest {
    private val h = PostgresHarness()

    @BeforeTest
    fun clean() = TestPostgres.truncate("messaging")

    private fun outboxRows(type: String) = h.count("SELECT count(*) FROM messaging.outbox WHERE type = ?", type)

    private fun memberRows(chatId: ChatId) =
        h.count("SELECT count(*) FROM messaging.members WHERE chat_id = ?", chatId.value)

    // ---- M4 ----

    @Test
    fun `of many opens of one pair from both sides, one chat is made, once, and everyone gets it (50 runs)`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) { run ->
                val (amina, brian) = List(2) { UserId.new() }
                val createdBefore = outboxRows(ChatCreated.TYPE)

                val opened =
                    concurrently(8) { i ->
                        if (i % 2 == 0) h.openDirectChat(amina, brian).data() else h.openDirectChat(brian, amina).data()
                    }

                val chatId = opened.first().chat.chatId
                assertEquals(1, opened.map { it.chat.chatId }.toSet().size, "one chat for the pair, run $run")
                assertEquals(1, opened.count { it.created == true }, "run $run")
                assertEquals(chatId, h.chats.directChat(brian, amina)!!.chatId)
                assertEquals(2, memberRows(chatId))
                assertEquals(createdBefore + 1, outboxRows(ChatCreated.TYPE), "ChatCreated once, run $run")
            }
        }

    // ---- M9 cap ----

    @Test
    fun `of many adds racing for the last place of a 999-member group, exactly one gets in`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val chatId = h.group(owner, List(998) { UserId.new() })
            assertEquals(999, h.chats.chat(chatId)!!.memberCount)
            val hopefuls = List(8) { UserId.new() }

            val results = concurrently(hopefuls.size) { i -> h.addMembers(owner, chatId, setOf(hopefuls[i])) }

            assertEquals(1, results.count { it is Resource.Success })
            assertEquals(
                List(7) { "messaging.group_full" },
                results.filterIsInstance<Resource.Error>().map { it.error.code },
            )
            val chat = h.chats.chat(chatId)!!
            assertEquals(1_000, chat.memberCount)
            assertEquals(1_000, memberRows(chatId), "the count is the rows")
            assertEquals(998 + 1, outboxRows(MemberAdded.TYPE), "the creation's and the one add's")
        }

    @Test
    fun `of many adds racing for a group's last place, exactly one gets in (50 runs)`() =
        runBlocking<Unit> {
            // The same statements as at 1,000, with a cap of 10 so 50 runs stay quick.
            val small = PostgresHarness(MessagingConfig(maxGroupMembers = 10))
            repeat(RACE_RUNS) { run ->
                val owner = UserId.new()
                val chatId = small.group(owner, List(8) { UserId.new() })
                val hopefuls = List(6) { UserId.new() }

                val results = concurrently(hopefuls.size) { i -> small.addMembers(owner, chatId, setOf(hopefuls[i])) }

                assertEquals(1, results.count { it is Resource.Success }, "run $run")
                assertTrue(results.filterIsInstance<Resource.Error>().all { it.error.code == "messaging.group_full" })
                assertEquals(10, small.chats.chat(chatId)!!.memberCount, "run $run")
                assertEquals(10, memberRows(chatId), "the count is the rows, run $run")
            }
        }

    @Test
    fun `an add that would pass the cap reserves nothing and writes nothing`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val chatId = h.group(owner, List(997) { UserId.new() })
            val version = h.chats.chat(chatId)!!.version

            assertEquals(
                "messaging.group_full",
                h.addMembers(owner, chatId, List(3) { UserId.new() }.toSet()).errorCode(),
            )
            assertNull(h.chats.reserveSeats(chatId, 3, 1_000), "the conditional update finds no row")
            assertEquals(998, h.chats.chat(chatId)!!.memberCount)
            assertEquals(version, h.chats.chat(chatId)!!.version)
        }

    // ---- M10 ----

    @Test
    fun `a removal takes the member, decrements the count and writes MemberRemoved in one transaction`() =
        runBlocking<Unit> {
            val (owner, brian, chidi) = List(3) { UserId.new() }
            val chatId = h.group(owner, listOf(brian, chidi))
            val before = h.chats.chat(chatId)!!

            h.removeMember(owner, chatId, brian).data()

            val after = h.chats.chat(chatId)!!
            assertNull(h.chats.member(chatId, brian))
            assertEquals(before.memberCount - 1, after.memberCount)
            assertEquals(before.version + 1, after.version)
            assertEquals(1, outboxRows(MemberRemoved.TYPE))
        }

    @Test
    fun `a removal whose outbox write fails leaves the member, the count and the outbox as they were`() =
        runBlocking<Unit> {
            val failing = PostgresHarness(outbox = { FailingOutbox(MessagingOutboxImpl(it), MemberRemoved.TYPE) })
            val (owner, brian) = List(2) { UserId.new() }
            val chatId = failing.group(owner, listOf(brian))
            val before = failing.chats.chat(chatId)!!

            assertFailsWith<IllegalStateException> { failing.leaveGroup(brian, chatId) }

            assertNotNull(failing.chats.member(chatId, brian), "still a member: the removal rolled back")
            assertEquals(before, failing.chats.chat(chatId))
            assertEquals(0, outboxRows(MemberRemoved.TYPE))
            PostgresHarness().leaveGroup(brian, chatId).data()
            assertNull(failing.chats.member(chatId, brian), "a retry completes it")
            assertEquals(1, outboxRows(MemberRemoved.TYPE))
        }

    @Test
    fun `of a removal and a leave of the same person at once, exactly one claims it (50 runs)`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) {
                val (owner, brian, chidi) = List(3) { UserId.new() }
                val chatId = h.group(owner, listOf(brian, chidi))
                val count = h.chats.chat(chatId)!!.memberCount
                val removedBefore = outboxRows(MemberRemoved.TYPE)

                val results =
                    concurrently(4) { i ->
                        if (i % 2 == 0) h.removeMember(owner, chatId, brian) else h.leaveGroup(brian, chatId)
                    }

                assertEquals(1, results.count { it is Resource.Success }, "run $it")
                assertTrue(
                    results.filterIsInstance<Resource.Error>().all { it.error.code == "messaging.chat_not_found" },
                )
                assertEquals(count - 1, h.chats.chat(chatId)!!.memberCount, "decremented once, run $it")
                assertEquals(removedBefore + 1, outboxRows(MemberRemoved.TYPE), "one event, run $it")
            }
        }

    @Test
    fun `the owner and the next in line leaving at once leave the group exactly one owner (50 runs)`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) {
                val (owner, first, second) = List(3) { UserId.new() }
                val chatId = h.group(owner, listOf(first, second))
                h.setRole(owner, chatId, first, Role.ADMIN).data()

                concurrently(2) { i -> h.leaveGroup(if (i == 0) owner else first, chatId).data() }

                val chat = h.chats.chat(chatId)!!
                assertEquals(second, chat.ownerId, "chosen from the settled member list")
                assertEquals(Role.OWNER, h.chats.member(chatId, second)!!.role)
                assertEquals(1, chat.memberCount)
            }
        }

    @Test
    fun `the last two leaving at once delete the group, once (50 runs)`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) { run ->
                val (owner, brian) = List(2) { UserId.new() }
                val chatId = h.group(owner, listOf(brian))
                val deletedBefore = outboxRows(GroupDeleted.TYPE)
                val removedBefore = outboxRows(MemberRemoved.TYPE)

                concurrently(2) { i -> h.leaveGroup(if (i == 0) owner else brian, chatId).data() }

                assertNull(h.chats.chat(chatId), "run $run")
                assertEquals(0, memberRows(chatId))
                assertEquals(deletedBefore + 1, outboxRows(GroupDeleted.TYPE), "deleted once, run $run")
                assertEquals(removedBefore + 2, outboxRows(MemberRemoved.TYPE))
            }
        }

    // ---- M11, M8 ----

    @Test
    fun `of two handovers at once, the second sees the first and the group keeps one owner (50 runs)`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) { run ->
                val (owner, brian, chidi) = List(3) { UserId.new() }
                val chatId = h.group(owner, listOf(brian, chidi))

                val results =
                    concurrently(2) { i -> h.setRole(owner, chatId, if (i == 0) brian else chidi, Role.OWNER) }

                assertEquals(1, results.count { it is Resource.Success }, "run $run")
                assertEquals(
                    "messaging.not_admin",
                    results
                        .filterIsInstance<Resource.Error>()
                        .single()
                        .error.code,
                )
                val owners = h.chats.members(chatId).filter { it.role == Role.OWNER }
                assertEquals(listOf(h.chats.chat(chatId)!!.ownerId), owners.map { it.userId }, "run $run")
            }
        }

    @Test
    fun `a role is written only on a member row that exists`() =
        runBlocking<Unit> {
            val (owner, brian) = List(2) { UserId.new() }
            val chatId = h.group(owner, listOf(brian))
            h.leaveGroup(brian, chatId).data()

            assertFalse(h.chats.setRole(Member(chatId, brian, Role.ADMIN, Instant.now())))
            assertNull(h.chats.member(chatId, brian))
        }

    @Test
    fun `a versioned update wins once and the stale one is refused`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val chatId = h.group(owner, listOf(UserId.new()))
            val chat = h.chats.chat(chatId)!!

            val results = concurrently(4) { h.chats.updateChat(chat, chat.copy(metaVersion = chat.metaVersion + 1)) }

            assertEquals(1, results.count { it != null })
            assertEquals(chat.version + 1, h.chats.chat(chatId)!!.version)
        }

    // ---- M6 and the markers ----

    @Test
    fun `the chat list reads each chat's newest MESSAGE, and none for a chat without one`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val direct = h.direct(amina, brian)
            val group = h.group(amina, listOf(brian))
            val first = h.sendMessage(h.directCommand(amina, brian, direct)).data()
            val last = h.sendMessage(h.directCommand(brian, amina, direct)).data()
            h.sendMessage(h.groupCommand(amina, group, MessageKind.CONTROL)).data()

            val lines = h.chats.chatList(brian).associateBy { it.chat.chatId }

            assertEquals(last.messageId, lines.getValue(direct).lastMessageId)
            assertTrue(first.messageId.toString() < last.messageId.toString())
            assertNull(lines.getValue(group).lastMessageId, "a control message is not shown")
            assertEquals(lines.keys, h.chats.chatIdsOf(brian).toSet())
            assertEquals(last.messageId, h.chats.lastMessageId(direct))
        }

    @Test
    fun `lastMessageId is null once the record's partition is past 30 days, and the members stay`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val chatId = h.direct(amina, brian)
            val old = LocalDate.now(ZoneOffset.UTC).minusDays(40)
            val partition = "message_records_p" + old.toString().replace("-", "")
            val from = lowestUuid7(old.atStartOfDay().toInstant(ZoneOffset.UTC))
            val to = lowestUuid7(old.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))
            TestPostgres.superuser { connection ->
                connection.createStatement().use {
                    it.execute(
                        "CREATE TABLE messaging.$partition PARTITION OF messaging.message_records " +
                            "FOR VALUES FROM ('$from') TO ('$to')",
                    )
                    it.execute("ALTER TABLE messaging.$partition OWNER TO messaging")
                }
                connection
                    .prepareStatement("INSERT INTO messaging.message_records VALUES (?, ?, ?, 'MESSAGE', now())")
                    .use {
                        it.setObject(1, UUID.fromString(from.replaceRange(30, 36, "000001")))
                        it.setObject(2, chatId.value)
                        it.setObject(3, amina.value)
                        it.executeUpdate()
                    }
            }
            assertNotNull(h.chats.lastMessageId(chatId), "kept while its day is within 30 days of the drop")

            MessagingExpiry(h.database).run()

            assertNull(h.chats.lastMessageId(chatId))
            assertNull(
                h.chats
                    .chatList(brian)
                    .single()
                    .lastMessageId,
            )
            assertEquals(2, memberRows(chatId), "the chat and its members stay")
            assertEquals(0, headRows(chatId), "the head went with its record's partition")
        }

    private fun headRows(chatId: ChatId) =
        h.count("SELECT count(*) FROM messaging.chat_heads WHERE chat_id = ?", chatId.value)

    /** A `message_records` row written as the superuser, as an older send that commits late would write it. */
    private fun insertRecord(
        messageId: UUID,
        chatId: ChatId,
        sender: UserId,
        kind: MessageKind,
    ) = TestPostgres.superuser { connection ->
        connection
            .prepareStatement("INSERT INTO messaging.message_records VALUES (?, ?, ?, ?, now())")
            .use {
                it.setObject(1, messageId)
                it.setObject(2, chatId.value)
                it.setObject(3, sender.value)
                it.setString(4, kind.name)
                it.executeUpdate()
            }
    }

    @Test
    fun `a chat's head only moves forward, only for a MESSAGE, and goes with the chat`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val chatId = h.direct(amina, brian)
            assertEquals(0, headRows(chatId), "no head before the first message")
            val last = h.sendMessage(h.directCommand(amina, brian, chatId)).data()
            assertEquals(last.messageId, h.chats.lastMessageId(chatId))

            val today = LocalDate.now(ZoneOffset.UTC).atStartOfDay().toInstant(ZoneOffset.UTC)
            val older = UUID.fromString(lowestUuid7(today).replaceRange(30, 36, "000001"))
            insertRecord(older, chatId, brian, MessageKind.MESSAGE)
            assertEquals(last.messageId, h.chats.lastMessageId(chatId), "an older send committing late")
            insertRecord(Uuid7.generate(Instant.now().plusSeconds(1)), chatId, brian, MessageKind.CONTROL)
            assertEquals(last.messageId, h.chats.lastMessageId(chatId), "a control message is not shown")

            h.chats.deleteChat(chatId)
            assertEquals(0, headRows(chatId))
        }

    @Test
    fun `V5's backfill gives each chat with a MESSAGE its newest one, and no head to the others`() =
        runBlocking<Unit> {
            val (amina, brian, chidi) = List(3) { UserId.new() }
            val direct = h.direct(amina, brian)
            val group = h.group(amina, listOf(brian, chidi))
            val quiet = h.group(chidi, listOf(amina))
            h.sendMessage(h.directCommand(amina, brian, direct)).data()
            val lastDirect = h.sendMessage(h.directCommand(brian, amina, direct)).data()
            val lastGroup = h.sendMessage(h.groupCommand(chidi, group)).data()
            h.sendMessage(h.groupCommand(amina, group, MessageKind.CONTROL)).data()
            h.sendMessage(h.groupCommand(chidi, quiet, MessageKind.CONTROL)).data()
            val script = javaClass.getResource("/db/messaging/V5__chat_heads.sql")!!.readText()
            val backfill = script.substringAfter("-- Backfill").let { it.substring(it.indexOf("INSERT INTO")) }

            TestPostgres.superuser { connection ->
                connection.createStatement().use {
                    it.execute("DELETE FROM messaging.chat_heads")
                    it.execute(backfill)
                }
            }

            assertEquals(lastDirect.messageId, h.chats.lastMessageId(direct))
            assertEquals(lastGroup.messageId, h.chats.lastMessageId(group))
            assertNull(h.chats.lastMessageId(quiet))
        }

    @Test
    fun `the chat list with members holds every member of the user's chats, and nothing of other chats`() =
        runBlocking<Unit> {
            val (amina, brian, chidi, dalia) = List(4) { UserId.new() }
            val direct = h.direct(amina, brian)
            val group = h.group(amina, listOf(brian, chidi))
            val elsewhere = h.group(chidi, listOf(dalia))
            val last = h.sendMessage(h.groupCommand(amina, group)).data()

            val listing = h.chats.chatListWithMembers(brian)

            assertEquals(h.chats.chatList(brian), listing.lines)
            assertEquals(setOf(direct, group), listing.members.keys)
            assertEquals(
                setOf(amina, brian),
                listing.members
                    .getValue(direct)
                    .map { it.userId }
                    .toSet(),
            )
            assertEquals(
                setOf(amina, brian, chidi),
                listing.members
                    .getValue(group)
                    .map { it.userId }
                    .toSet(),
            )
            assertEquals(last.messageId, listing.lines.single { it.chat.chatId == group }.lastMessageId)
            assertTrue(elsewhere !in listing.members.keys)
            assertEquals(ChatListing(emptyList(), emptyMap()), h.chats.chatListWithMembers(UserId.new()))
        }

    @Test
    fun `the read marker only moves forward, and only a member's`() =
        runBlocking<Unit> {
            val (amina, brian) = List(2) { UserId.new() }
            val chatId = h.direct(amina, brian)
            val first = h.sendMessage(h.directCommand(amina, brian, chatId)).data().messageId
            val second = h.sendMessage(h.directCommand(amina, brian, chatId)).data().messageId

            assertTrue(h.chats.moveReadMarker(brian, chatId, second))
            assertFalse(h.chats.moveReadMarker(brian, chatId, first), "never back")
            assertFalse(h.chats.moveReadMarker(brian, chatId, second), "nor to where it is")
            assertFalse(h.chats.moveReadMarker(UserId.new(), chatId, second))
            assertEquals(second, h.chats.userChat(brian, chatId)!!.readUpTo)
        }

    @Test
    fun `two people share a group, and a direct chat is not one`() =
        runBlocking<Unit> {
            val (amina, brian, chidi) = List(3) { UserId.new() }
            h.direct(amina, chidi)
            h.group(amina, listOf(brian))

            assertTrue(h.chats.shareGroup(amina, brian))
            assertTrue(h.chats.shareGroup(brian, amina))
            assertFalse(h.chats.shareGroup(amina, chidi))
        }

    @Test
    fun `a group create is one transaction with its events`() =
        runBlocking<Unit> {
            val failing = PostgresHarness(outbox = { FailingOutbox(MessagingOutboxImpl(it), ChatCreated.TYPE) })
            val owner = UserId.new()

            assertFailsWith<IllegalStateException> { failing.group(owner, listOf(UserId.new())) }

            assertEquals(0, h.count("SELECT count(*) FROM messaging.chats"))
            assertEquals(0, h.count("SELECT count(*) FROM messaging.members"))
            assertEquals(0, h.count("SELECT count(*) FROM messaging.outbox"))
        }
}

/** Writes through, then fails the transaction when [failOn] is among the events, after its row went in. */
class FailingOutbox(private val outbox: MessagingOutbox, private val failOn: String) : MessagingOutbox {
    override suspend fun write(events: List<EventEnvelope>) {
        outbox.write(events)
        check(events.none { it.type == failOn }) { "the outbox write failed" }
    }
}
