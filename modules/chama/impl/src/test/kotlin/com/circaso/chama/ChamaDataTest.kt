package com.circaso.chama

import com.circaso.chama.data.repositories.ContributionRepositoryImpl
import com.circaso.chama.data.repositories.DirectPayoutRepositoryImpl
import com.circaso.chama.data.repositories.GoalRepositoryImpl
import com.circaso.chama.data.repositories.RequestRepositoryImpl
import com.circaso.chama.data.repositories.SettingsRepositoryImpl
import com.circaso.chama.di.chamaDatabaseSpec
import com.circaso.chama.domain.models.ApprovalPolicy
import com.circaso.chama.domain.models.Contribution
import com.circaso.chama.domain.models.DirectPayout
import com.circaso.chama.domain.models.Goal
import com.circaso.chama.domain.models.GroupSettings
import com.circaso.chama.presentation.warmup.ChamaWarmUp
import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.api.ChatId
import com.circaso.shared.UserId
import com.circaso.wallet.api.TransferId
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Chama's data layer on PostgreSQL after V3: a target without a treasurer, removing it, the group's approval setting
 * with its default, totals that skip legacy contributions and count from a target's start, and direct payouts (V3).
 */
class ChamaDataTest {
    private val database: Database = TestPostgres.database(chamaDatabaseSpec)
    private val goals = GoalRepositoryImpl(database)
    private val contributions = ContributionRepositoryImpl(database)
    private val settings = SettingsRepositoryImpl(database)
    private val payouts = DirectPayoutRepositoryImpl(database)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeTest
    fun clean() =
        runBlocking<Unit> {
            TestPostgres.truncate("chama")
        }

    @Test
    fun `the warm-up runs every chama read against the real tables and writes nothing`() =
        runBlocking<Unit> {
            ChamaWarmUp(goals, settings, contributions, RequestRepositoryImpl(database)).run()

            val rows =
                TestPostgres.superuser { connection ->
                    val tables =
                        connection.createStatement().use { statement ->
                            statement
                                .executeQuery(
                                    "SELECT table_name FROM information_schema.tables " +
                                        "WHERE table_schema = 'chama' AND table_type = 'BASE TABLE' " +
                                        // Flyway's own history, which truncate keeps: one row per migration
                                        "AND table_name <> 'flyway_schema_history'",
                                ).use { result ->
                                    generateSequence { if (result.next()) result.getString(1) else null }.toList()
                                }
                        }
                    tables.sumOf { table ->
                        connection.createStatement().use { statement ->
                            statement.executeQuery("SELECT count(*) FROM chama.\"$table\"").use {
                                it.next()
                                it.getLong(1)
                            }
                        }
                    }
                }
            assertEquals(0L, rows, "read-only: every chama table is still empty")
            assertNull(goals.find(ChatId.new()))
        }

    @Test
    fun `a target without a treasurer is saved, read back and removed`() =
        runBlocking<Unit> {
            val chat = ChatId.new()
            val owner = UserId.new()
            val goal = Goal(chat, "Holiday Fund", 50_000_000, null, null, owner, now, now, 1)
            goals.save(goal)

            assertEquals(goal, goals.find(chat))
            assertTrue(goals.delete(chat))
            assertNull(goals.find(chat))
            assertFalse(goals.delete(chat))
        }

    @Test
    fun `a group that never chose needs two admins, and its owner's choice is kept`() =
        runBlocking<Unit> {
            val chat = ChatId.new()
            assertEquals(GroupSettings.default(chat), settings.find(chat))

            val chosen = GroupSettings(chat, ApprovalPolicy.ANY_ADMIN, UserId.new(), now)
            settings.save(chosen)
            assertEquals(chosen, settings.find(chat))
            settings.save(chosen.copy(approvalPolicy = ApprovalPolicy.TWO_ADMINS))
            assertEquals(ApprovalPolicy.TWO_ADMINS, settings.find(chat).approvalPolicy)
        }

    @Test
    fun `totals skip legacy contributions and count from when a target started`() =
        runBlocking<Unit> {
            val chat = ChatId.new()
            val amina = UserId.new()
            val bob = UserId.new()
            contributions.insert(Contribution(TransferId.new(), chat, amina, 100, now.minusSeconds(60)))
            contributions.insert(Contribution(TransferId.new(), chat, amina, 1_000, now, legacy = true))
            contributions.insert(Contribution(TransferId.new(), chat, bob, 20, now))

            assertEquals(mapOf(amina to 100L, bob to 20L), contributions.totals(chat))
            assertEquals(mapOf(bob to 20L), contributions.totals(chat, since = now))
            assertEquals(3, contributions.list(chat, 10, null).size, "the ledger keeps every line")
            assertTrue(contributions.list(chat, 10, null).any { it.legacy })
        }

    @Test
    fun `a direct payout is recorded once per transfer and totalled per group`() =
        runBlocking<Unit> {
            val chat = ChatId.new()
            val t = TransferId.new()
            assertTrue(payouts.insert(DirectPayout(t, chat, 300, now)))
            assertFalse(payouts.insert(DirectPayout(t, chat, 300, now)), "the same transfer twice")
            payouts.insert(DirectPayout(TransferId.new(), chat, 50, now))
            payouts.insert(DirectPayout(TransferId.new(), ChatId.new(), 7, now))

            assertEquals(350L, payouts.total(chat))
            assertEquals(0L, payouts.total(ChatId.new()))
        }
}
