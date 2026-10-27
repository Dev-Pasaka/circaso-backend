package com.circaso.assistant

import com.circaso.assistant.data.cache.AesTextVault
import com.circaso.assistant.data.repositories.AssistantExpiry
import com.circaso.assistant.data.repositories.AssistantStoreImpl
import com.circaso.assistant.data.repositories.ChatRepositoryImpl
import com.circaso.assistant.data.repositories.DatabaseTransactor
import com.circaso.assistant.data.repositories.EntitlementRepositoryImpl
import com.circaso.assistant.di.assistantDatabaseSpec
import com.circaso.assistant.domain.models.ChatMessage
import com.circaso.assistant.domain.models.Plan
import com.circaso.assistant.domain.models.ReadNote
import com.circaso.assistant.domain.models.Role
import com.circaso.assistant.domain.models.VoiceClip
import com.circaso.assistant.domain.repositories.AssistantChanges
import com.circaso.assistant.domain.repositories.AuditEntry
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.assistant.domain.support.today
import com.circaso.assistant.domain.usecases.DeleteAssistantData
import com.circaso.assistant.domain.usecases.DeleteChat
import com.circaso.assistant.domain.usecases.DeleteExchange
import com.circaso.assistant.domain.usecases.TranscribeVoice
import com.circaso.identity.api.AccountDeleted
import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.Secrets
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The assistant's data layer on a real PostgreSQL (infrastructure's test container), migrated by its own Flyway
 * scripts: every repository method, the races the database settles, the deletion handler, the partition job and the
 * transactions' atomicity.
 */
class DataLayerTest {
    private val database: Database = TestPostgres.database(assistantDatabaseSpec)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)

    private val vault =
        AesTextVault(
            object : Secrets {
                override fun get(name: String): String? = null
            },
            local = true,
        )
    private val transactor = DatabaseTransactor(database)
    private val chat = ChatRepositoryImpl(database, vault)
    private val store = AssistantStoreImpl(database, vault, clock)
    private val deleteExchange = DeleteExchange(chat, transactor, store)
    private val deleteChat = DeleteChat(transactor, store)
    private val deleteData = DeleteAssistantData(transactor, store, MemoryPending(), MemoryQuota(), clock)

    @BeforeTest
    fun clean() = TestPostgres.truncate("assistant")

    private fun count(sql: String, vararg values: Any): Int =
        TestPostgres.superuser { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.executeQuery().use { rows ->
                    rows.next()
                    rows.getInt(1)
                }
            }
        }

    private fun text(sql: String, vararg values: Any): String? =
        TestPostgres.superuser { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }

    private fun entitle(
        user: UserId,
        plan: String,
        endsAt: Instant?,
    ) = TestPostgres.superuser { connection ->
        connection
            .prepareStatement(
                "INSERT INTO assistant.entitlements (user_id, plan, plan_ends_at) VALUES (?, ?, ?) " +
                    "ON CONFLICT (user_id) DO UPDATE SET plan = EXCLUDED.plan, plan_ends_at = EXCLUDED.plan_ends_at",
            ).use { statement ->
                statement.setObject(1, user.value)
                statement.setString(2, plan)
                statement.setTimestamp(3, endsAt?.let(Timestamp::from))
                statement.executeUpdate()
            }
    }

    private fun message(
        user: UserId,
        text: String,
        role: Role = Role.USER,
        at: Instant = now,
        replyTo: UUID? = null,
        answeredBy: UUID? = null,
        note: ReadNote? = null,
    ) = ChatMessage(
        user,
        Uuid7.generate(at),
        role,
        text,
        replyTo,
        answeredBy,
        if (role == Role.USER) "Family" else null,
        note,
        at,
    )

    /** A question and Asha's answer to it, linked both ways, as a turn saves them. */
    private fun exchange(user: UserId, at: Instant = now): Pair<ChatMessage, ChatMessage> {
        val questionId = Uuid7.generate(at)
        val answerId = Uuid7.generate(at.plusMillis(1))
        val question = ChatMessage(user, questionId, Role.USER, "q", null, answerId, null, null, at)
        val answer = ChatMessage(user, answerId, Role.ASHA, "a", questionId, null, null, null, at)
        return question to answer
    }

    private suspend fun <T> concurrently(times: Int, block: suspend (Int) -> T): List<T> =
        coroutineScope { (0 until times).map { async(Dispatchers.Default) { block(it) } }.awaitAll() }

    // ---- the chat ----

    @Test
    fun `text is sealed in the table and comes back whole, with the read note and the links`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val note = ReadNote(listOf("family"), 4, listOf("Family"))
            val question = message(user, "What did Mum say about the rent?")
            val answer = message(user, "She said Friday.", Role.ASHA, replyTo = question.messageId, note = note)

            store.commit(AssistantChanges(messages = listOf(question, answer)))

            val body = text("SELECT body FROM assistant.asha_messages WHERE message_id = ?", question.messageId)
            assertFalse("rent" in body.orEmpty(), "the database holds sealed text")
            val read = chat.find(user, question.messageId)!!
            assertEquals("What did Mum say about the rent?", read.text)
            assertEquals("Family", read.askedFrom)
            assertEquals(note, chat.find(user, answer.messageId)?.readNote)
            assertEquals(question.messageId, chat.find(user, answer.messageId)?.replyTo)
            assertNull(chat.find(UserId.new(), question.messageId), "only its owner finds it")
            assertEquals(
                1,
                count("SELECT count(*) FROM assistant.asha_messages WHERE read_note IS NULL AND reply_to IS NULL"),
                "a cleared value is a real NULL",
            )
        }

    @Test
    fun `the chat lists newest first and pages by keyset on the message id`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val all = (0 until 5).map { message(user, "m$it", at = now.plusMillis(it * 10L)) }
            store.commit(AssistantChanges(messages = all + message(UserId.new(), "not mine")))

            val first = chat.list(user, null, 3)
            assertEquals(listOf("m4", "m3", "m2"), first.map { it.text })
            assertEquals(listOf("m1", "m0"), chat.list(user, first.last().messageId, 10).map { it.text })
            assertTrue(chat.list(user, all.first().messageId, 10).isEmpty())
        }

    @Test
    fun `deleting messages counts what it removed and touches only the owner's`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val other = UserId.new()
            val mine = message(user, "mine")
            val theirs = message(other, "theirs")
            store.commit(AssistantChanges(messages = listOf(mine, theirs)))

            assertEquals(0, chat.delete(user, listOf(theirs.messageId)), "another user's message is not mine to delete")
            assertEquals(1, chat.delete(user, listOf(mine.messageId, UUID.randomUUID())))
            assertEquals(0, chat.delete(user, listOf(mine.messageId)), "gone already")
            assertEquals(0, chat.delete(user, emptyList()))
            assertEquals("theirs", chat.find(other, theirs.messageId)?.text)
        }

    @Test
    fun `deleting an exchange removes the question and the answer, named by either, with one audit record`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val (question, answer) = exchange(user)
            val (keptQuestion, keptAnswer) = exchange(user, now.plusSeconds(1))
            store.commit(AssistantChanges(messages = listOf(question, answer, keptQuestion, keptAnswer)))

            assertEquals(Resource.success(Unit), deleteExchange(user, answer.messageId, "req-6"))

            assertNull(chat.find(user, question.messageId))
            assertNull(chat.find(user, answer.messageId))
            assertEquals(2, chat.list(user, null, 10).size, "the other exchange stays")
            assertEquals(1, count("SELECT count(*) FROM assistant.audit WHERE kind = 'EXCHANGE_DELETED'"))
            assertEquals(
                Resource.error(SharedError.NotFound),
                deleteExchange(user, question.messageId, "req-7"),
            )
        }

    @Test
    fun `of many deletes of one exchange at once exactly one succeeds and writes its audit record`() =
        runBlocking<Unit> {
            repeat(10) {
                val user = UserId.new()
                val (question, answer) = exchange(user)
                store.commit(AssistantChanges(messages = listOf(question, answer)))

                val results =
                    concurrently(6) { i ->
                        deleteExchange(user, if (i % 2 == 0) question.messageId else answer.messageId, null)
                    }

                assertEquals(1, results.count { it is Resource.Success }, results.toString())
                assertTrue(results.filterIsInstance<Resource.Error>().all { it.error == SharedError.NotFound })
                assertEquals(
                    1,
                    count(
                        "SELECT count(*) FROM assistant.audit WHERE user_id = ? AND kind = 'EXCHANGE_DELETED'",
                        user.value,
                    ),
                )
            }
        }

    @Test
    fun `deleting a whole long chat removes it with its audit record and leaves other chats alone`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val other = UserId.new()
            val mine = (0 until 300).map { message(user, "m$it", at = now.plusMillis(it.toLong())) }
            val theirs = message(other, "keep me")
            store.commit(AssistantChanges(messages = mine + theirs))

            assertEquals(Resource.success(Unit), deleteChat(user, "req-5"))

            assertTrue(chat.list(user, null, 500).isEmpty())
            assertEquals("keep me", chat.find(other, theirs.messageId)?.text, "another user's chat is untouched")
            assertEquals(
                "req-5",
                text("SELECT request_id FROM assistant.audit WHERE kind = 'CHAT_DELETED' AND user_id = ?", user.value),
            )
            assertEquals(0, store.deleteChat(user), "nothing left to delete")
        }

    @Test
    fun `of many deletes of one chat at once one records it, and deleting an empty chat records nothing`() =
        runBlocking<Unit> {
            repeat(50) {
                val user = UserId.new()
                val messages = (0 until 20).map { message(user, "m$it", at = now.plusMillis(it.toLong())) }
                store.commit(AssistantChanges(messages = messages))

                val results = concurrently(6) { deleteChat(user, "req-$it") }

                assertTrue(results.all { it == Resource.success(Unit) }, "deleting an empty chat is not an error")
                val audited = "SELECT count(*) FROM assistant.audit WHERE user_id = ? AND kind = 'CHAT_DELETED'"
                assertEquals(1, count(audited, user.value))
                assertEquals(Resource.success(Unit), deleteChat(user, "again"))
                assertEquals(1, count(audited, user.value), "an empty chat records nothing")
            }
        }

    // ---- entitlements ----

    @Test
    fun `the plan is free until an entitlement says otherwise and ends with it`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val entitlements = EntitlementRepositoryImpl(database, clock)
            assertEquals(Plan.FREE, entitlements.planOf(user))

            entitle(user, "PLUS", now.plusSeconds(3600))
            assertEquals(Plan.PLUS, entitlements.planOf(user))
            val later = EntitlementRepositoryImpl(database, Clock.offset(clock, Duration.ofHours(2)))
            assertEquals(Plan.FREE, later.planOf(user), "a plan that has ended is the free plan")

            entitle(user, "PLUS", null)
            assertEquals(Plan.PLUS, later.planOf(user), "no end date: the plan holds")

            entitle(user, "GOLD", null)
            assertEquals(Plan.FREE, entitlements.planOf(user), "an unknown plan is the free plan")
        }

    // ---- usage and audit ----

    @Test
    fun `usage totals are kept per day and never go down when an older total is written late`() =
        runBlocking<Unit> {
            val user = UserId.new()
            store.saveUsage(user, "2026-10-22", 3, 12, 900, 140, now)
            store.saveUsage(user, "2026-10-22", 2, 20, 800, 150, now.plusSeconds(1))
            store.saveUsage(user, "2026-10-23", 1, 0, 10, 5, now)

            assertEquals(
                "3 20 900 150",
                text(
                    "SELECT concat_ws(' ', text_turns, voice_seconds, input_tokens, output_tokens) " +
                        "FROM assistant.usage_daily WHERE user_id = ? AND usage_day = '2026-10-22'",
                    user.value,
                ),
            )
            assertEquals(2, count("SELECT count(*) FROM assistant.usage_daily WHERE user_id = ?", user.value))
        }

    @Test
    fun `many turns saving the day's totals at once store the largest`() =
        runBlocking<Unit> {
            val user = UserId.new()

            concurrently(20) { i -> store.saveUsage(user, "2026-10-22", i, i * 2, i * 100L, i * 10L, now) }

            assertEquals(
                "19 38 1900 190",
                text(
                    "SELECT concat_ws(' ', text_turns, voice_seconds, input_tokens, output_tokens) " +
                        "FROM assistant.usage_daily WHERE user_id = ?",
                    user.value,
                ),
            )
        }

    @Test
    fun `a transcribed clip stores the day's voice seconds at once, never lowering what a turn stored`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val day = clock.today()
            val speech = RecordingSpeech().apply { seconds = 7 }
            val quota = MemoryQuota()
            val transcribe = TranscribeVoice(speech, Plans(), quota, store, transactor, AssistantConfig(), clock)
            store.saveUsage(user, day, 2, 0, 300, 40, now) // an earlier text turn's totals

            assertTrue(transcribe(user, VoiceClip("hello".toByteArray(), "audio/mp4", 7, null)) is Resource.Success)

            assertEquals(
                "2 7 300 40",
                text(
                    "SELECT concat_ws(' ', text_turns, voice_seconds, input_tokens, output_tokens) " +
                        "FROM assistant.usage_daily WHERE user_id = ? AND usage_day = ?::date",
                    user.value,
                    day,
                ),
            )
        }

    @Test
    fun `audit records are stored with their request id and ids that keep their order`() =
        runBlocking<Unit> {
            val user = UserId.new()
            store.commit(
                AssistantChanges(
                    audit =
                        listOf(
                            AuditEntry(user, "TURN", "tools=none", "req-1"),
                            AuditEntry(user, "CHAT_DELETED", "all", null),
                        ),
                ),
            )

            assertEquals(2, count("SELECT count(*) FROM assistant.audit WHERE user_id = ?", user.value))
            assertEquals("req-1", text("SELECT request_id FROM assistant.audit WHERE kind = 'TURN'"))
            assertEquals(
                1,
                count("SELECT count(*) FROM assistant.audit WHERE kind = 'CHAT_DELETED' AND request_id IS NULL"),
            )
            assertEquals(
                2,
                count("SELECT count(*) FROM assistant.audit WHERE uuid_extract_version(event_id) = 7"),
                "UUIDv7, made in the app",
            )
        }

    // ---- transactions ----

    @Test
    fun `a refused attempt rolls back what it wrote, and the outbox waits empty for Phase B`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val keptMessage = message(user, "kept")
            val lostMessage = message(user, "lost", at = now.plusMillis(5))

            store.commit(AssistantChanges(messages = listOf(keptMessage)))
            val refused =
                transactor.attempt("test.refused") {
                    store.commit(
                        AssistantChanges(
                            messages = listOf(lostMessage),
                            audit = listOf(AuditEntry(user, "TURN", "tools=none", null)),
                        ),
                    )
                    Resource.error(SharedError.NotFound)
                }

            assertEquals(Resource.error(SharedError.NotFound), refused)
            assertEquals("kept", chat.find(user, keptMessage.messageId)?.text)
            assertNull(chat.find(user, lostMessage.messageId))
            assertEquals(0, count("SELECT count(*) FROM assistant.audit WHERE user_id = ?", user.value))
            assertEquals(0, count("SELECT count(*) FROM assistant.outbox"), "Phase A publishes no events")
        }

    @Test
    fun `a turn's messages, audit and usage land together or not at all`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val (question, answer) = exchange(user)

            runCatching {
                transactor.run("chat.record-turn") {
                    store.commit(
                        AssistantChanges(
                            messages = listOf(question, answer),
                            audit = listOf(AuditEntry(user, "TURN", "tools=none", null)),
                        ),
                    )
                    store.saveUsage(user, "2026-10-22", 1, 0, 10, 5, now)
                    error("the node died before the commit")
                }
            }

            assertNull(chat.find(user, question.messageId))
            assertEquals(0, count("SELECT count(*) FROM assistant.audit WHERE user_id = ?", user.value))
            assertEquals(0, count("SELECT count(*) FROM assistant.usage_daily WHERE user_id = ?", user.value))
        }

    // ---- account deletion ----

    @Test
    fun `a deleted account loses its chat and its plan in one go, keeps its audit and usage, and twice is harmless`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val other = UserId.new()
            val (question, answer) = exchange(user)
            val theirs = message(other, "keep me")
            store.commit(
                AssistantChanges(
                    messages = listOf(question, answer, theirs),
                    audit = listOf(AuditEntry(user, "TURN", "tools=none", null)),
                ),
            )
            store.saveUsage(user, "2026-10-22", 1, 0, 10, 5, now)
            entitle(user, "PLUS", null)
            entitle(other, "PLUS", null)

            assertEquals(Resource.success(Unit), deleteData(AccountDeleted(user)))
            assertEquals(Resource.success(Unit), deleteData(AccountDeleted(user)))

            assertEquals(0, count("SELECT count(*) FROM assistant.asha_messages WHERE user_id = ?", user.value))
            assertEquals(0, count("SELECT count(*) FROM assistant.entitlements WHERE user_id = ?", user.value))
            assertEquals(1, count("SELECT count(*) FROM assistant.usage_daily WHERE user_id = ?", user.value))
            assertEquals(
                1,
                count("SELECT count(*) FROM assistant.audit WHERE user_id = ? AND kind = 'TURN'", user.value),
            )
            assertTrue(
                count(
                    "SELECT count(*) FROM assistant.audit WHERE user_id = ? AND kind = 'ACCOUNT_DELETED'",
                    user.value,
                ) >=
                    1,
            )
            assertEquals("keep me", chat.find(other, theirs.messageId)?.text)
            assertEquals(Plan.PLUS, EntitlementRepositoryImpl(database, clock).planOf(other))
        }

    // ---- partitions ----

    @Test
    fun `the purge job keeps the outbox partitions a day ahead`() =
        runBlocking<Unit> {
            AssistantExpiry(database).run()
            AssistantExpiry(database).run()

            val tomorrow =
                Instant
                    .now()
                    .atZone(ZoneOffset.UTC)
                    .toLocalDate()
                    .plusDays(1)
                    .toString()
                    .replace("-", "")
            assertEquals(
                1,
                count(
                    "SELECT count(*) FROM pg_tables WHERE schemaname = 'assistant' AND tablename = ?",
                    "outbox_p$tomorrow",
                ),
            )
        }
}
