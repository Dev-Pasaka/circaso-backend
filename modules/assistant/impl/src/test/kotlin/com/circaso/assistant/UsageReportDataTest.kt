package com.circaso.assistant

import com.circaso.assistant.api.TokenRates
import com.circaso.assistant.api.UsageRates
import com.circaso.assistant.data.cache.AesTextVault
import com.circaso.assistant.data.repositories.AssistantStoreImpl
import com.circaso.assistant.data.repositories.DatabaseTransactor
import com.circaso.assistant.data.repositories.UsageReportRepositoryImpl
import com.circaso.assistant.di.assistantDatabaseSpec
import com.circaso.assistant.domain.models.TokenCounts
import com.circaso.assistant.domain.support.TokenTally
import com.circaso.assistant.domain.support.UsageTotals
import com.circaso.assistant.domain.support.saveUsage
import com.circaso.assistant.domain.usecases.CountAshaUsers
import com.circaso.assistant.domain.usecases.ListUsageDays
import com.circaso.assistant.domain.usecases.RankUsageUsers
import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.Secrets
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.integrations.api.ModelResponse
import com.circaso.integrations.api.ModelUsage
import com.circaso.shared.DomainException
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import java.io.File
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Tokens by model (V4, decision 2026-11-08) on a real PostgreSQL migrated by the module's own scripts: the per-model
 * `GREATEST` upsert, V4's backfill of older days, and the staff report's reads over a range.
 */
class UsageReportDataTest {
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
    private val store = AssistantStoreImpl(database, vault, clock)
    private val transactor = DatabaseTransactor(database)
    private val reports = UsageReportRepositoryImpl(database)
    private val haiku = "claude-haiku-5-5"
    private val nov1 = LocalDate.of(2026, 11, 1)
    private val nov2 = LocalDate.of(2026, 11, 2)
    private val nov3 = LocalDate.of(2026, 11, 3)

    @BeforeTest
    fun clean() = TestPostgres.truncate("assistant")

    private fun text(sql: String, vararg values: Any): String? =
        TestPostgres.superuser { connection ->
            connection.prepareStatement(sql).use { statement ->
                values.forEachIndexed { i, value -> statement.setObject(i + 1, value) }
                statement.executeQuery().use { rows -> if (rows.next()) rows.getString(1) else null }
            }
        }

    private suspend fun save(
        user: UserId,
        day: LocalDate,
        turns: Int = 1,
        voice: Int = 0,
        searches: Int = 0,
        byModel: Map<String, TokenCounts>,
    ) = transactor.run("test.save-usage") {
        val input = byModel.values.sumOf { it.input + it.cacheRead + it.cacheWrite }
        val output = byModel.values.sumOf { it.output }
        store.saveUsage(user, day.toString(), UsageTotals(turns, voice, input, output, searches, byModel), now)
    }

    @Test
    fun `a day's tokens are one row per model, and an older total written late never lowers them`() =
        runBlocking<Unit> {
            val user = UserId.new()
            val first = mapOf(haiku to TokenCounts(500, 300, 200, 90), "other-model" to TokenCounts(1, 0, 0, 1))
            save(user, nov1, byModel = first)
            save(user, nov1, byModel = mapOf(haiku to TokenCounts(400, 350, 100, 80)))

            assertEquals(
                "500 350 200 90",
                text(
                    "SELECT concat_ws(' ', input_tokens, cache_read_tokens, cache_write_tokens, output_tokens) " +
                        "FROM assistant.usage_daily_tokens WHERE user_id = ? AND model = ?",
                    user.value,
                    haiku,
                ),
            )
            assertEquals(
                "2",
                text("SELECT count(*) FROM assistant.usage_daily_tokens WHERE user_id = ?", user.value),
            )
        }

    @Test
    fun `many turns saving a model's totals at once store the largest`() =
        runBlocking<Unit> {
            val user = UserId.new()
            coroutineScope {
                (1..20)
                    .map { i ->
                        async(Dispatchers.IO) {
                            save(user, nov1, byModel = mapOf(haiku to TokenCounts(i * 10L, i * 5L, i.toLong(), i * 2L)))
                        }
                    }.awaitAll()
            }
            assertEquals(
                "200 100 20 40",
                text(
                    "SELECT concat_ws(' ', input_tokens, cache_read_tokens, cache_write_tokens, output_tokens) " +
                        "FROM assistant.usage_daily_tokens WHERE user_id = ?",
                    user.value,
                ),
            )
        }

    @Test
    fun `V4's backfill gives an older day its tokens as Claude Haiku 5_5's, none from the cache`() =
        runBlocking<Unit> {
            val user = UserId.new()
            store.saveUsage(user, "2026-10-30", 3, 0, 900, 140, now) // a day stored before V4: no per-model row
            store.saveUsage(UserId.new(), "2026-10-30", 0, 12, 0, 0, now) // voice only: no tokens, no row

            TestPostgres.superuser { connection -> connection.createStatement().use { it.execute(backfill()) } }

            assertEquals(
                "claude-haiku-5-5 900 0 0 140",
                text(
                    "SELECT concat_ws(' ', model, input_tokens, cache_read_tokens, cache_write_tokens, " +
                        "output_tokens) " +
                        "FROM assistant.usage_daily_tokens WHERE user_id = ?",
                    user.value,
                ),
            )
            assertEquals("1", text("SELECT count(*) FROM assistant.usage_daily_tokens"))
        }

    @Test
    fun `the report reads days, distinct users and the heaviest users over a half-open range`() =
        runBlocking<Unit> {
            val light = UserId.new()
            val heavy = UserId.new()
            val searcher = UserId.new()
            save(light, nov1, turns = 2, voice = 30, byModel = mapOf(haiku to TokenCounts(1_000, 0, 0, 100)))
            save(light, nov2, turns = 1, byModel = mapOf(haiku to TokenCounts(500, 0, 0, 50)))
            save(heavy, nov2, turns = 5, byModel = mapOf(haiku to TokenCounts(100_000, 50_000, 10_000, 20_000)))
            val heavyDay = TokenCounts(100_000, 50_000, 10_000, 20_000)
            save(
                heavy,
                nov2,
                turns = 5,
                byModel =
                    mapOf(
                        haiku to heavyDay,
                        "free-model" to TokenCounts(9_000_000, 0, 0, 0),
                    ),
            )
            save(searcher, nov2, turns = 1, searches = 40, byModel = mapOf(haiku to TokenCounts(10, 0, 0, 10)))
            save(heavy, nov3, turns = 9, byModel = mapOf(haiku to TokenCounts(999_999, 0, 0, 0))) // outside the range

            val days = ListUsageDays(reports)(nov1, nov3).getOrNull()!!
            assertEquals(listOf(nov1, nov2), days.map { it.activity.day })
            assertEquals(2L, days[0].activity.textTurns)
            assertEquals(30L, days[0].activity.voiceSeconds)
            assertEquals(3L, days[1].activity.users)
            assertEquals(40L, days[1].activity.webSearches)
            assertEquals(TokenCounts(100_510, 50_000, 10_000, 20_060), days[1].tokens.getValue(haiku))
            assertEquals(TokenCounts(9_000_000, 0, 0, 0), days[1].tokens.getValue("free-model"))
            assertEquals(3L, CountAshaUsers(reports)(nov1, nov3).getOrNull())

            // haiku at list prices; free-model has no price and weighs nothing; a search costs a cent.
            val rates =
                UsageRates(
                    mapOf(
                        haiku to
                            TokenRates(
                                BigDecimal("0.0000001"),
                                BigDecimal("0.00000001"),
                                BigDecimal("0.000000125"),
                                BigDecimal("0.0000005"),
                            ),
                    ),
                    perWebSearch = BigDecimal("0.01"),
                )
            // heavy: $0.01 + $0.0005 + $0.00125 + $0.01 = $0.02175; searcher: 40 × $0.01 = $0.40; light: $0.000225.
            val ranked = RankUsageUsers(reports)(nov1, nov3, rates, 10).getOrNull()!!
            assertEquals(listOf(searcher, heavy, light), ranked.map { it.activity.userId })
            assertEquals(40L, ranked[0].activity.webSearches)
            assertEquals(5L, ranked[1].activity.textTurns) // the same day saved twice keeps the larger total
            assertEquals(TokenCounts(1_500, 0, 0, 150), ranked[2].tokens.getValue(haiku))
            val topOne = RankUsageUsers(reports)(nov1, nov3, rates, 1).getOrNull()!!
            assertEquals(listOf(searcher), topOne.map { it.activity.userId })

            val none = UsageRates(emptyMap(), BigDecimal.ZERO)
            val unpriced = RankUsageUsers(reports)(nov1, nov3, none, 10).getOrNull()!!
            assertEquals(3, unpriced.size, "no prices still lists users")
        }

    @Test
    fun `a range must be 1 to 366 days`() =
        runBlocking<Unit> {
            assertFailsWith<DomainException> { ListUsageDays(reports)(nov2, nov2) }
            assertFailsWith<DomainException> { CountAshaUsers(reports)(nov2, nov1) }
            assertFailsWith<DomainException> { ListUsageDays(reports)(nov1, nov1.plusDays(367)) }
            assertTrue(ListUsageDays(reports)(nov1, nov1.plusDays(366)).getOrNull()!!.isEmpty())
        }

    @Test
    fun `a turn's tokens are kept by the model that answered, the fresh input apart from the cache`() {
        val tally = TokenTally()
        val cached = ModelUsage(100, 20, cacheReadTokens = 60, cacheWriteTokens = 30)
        tally.add(ModelResponse("a", emptyList(), cached, haiku))
        tally.add(ModelResponse("b", emptyList(), ModelUsage(50, 5, cacheReadTokens = 40), haiku))
        tally.add(ModelResponse("c", emptyList(), ModelUsage(10, 1)))
        tally.add(ModelResponse("d", emptyList(), ModelUsage(0, 0), "silent-model"))

        assertEquals(
            mapOf(haiku to TokenCounts(20, 100, 30, 25), TokenCounts.UNKNOWN_MODEL to TokenCounts(10, 0, 0, 1)),
            tally.snapshot(),
        )
    }

    /** V4's backfill statement, run again over a day stored the old way (the test schema is already at V4). */
    private fun backfill(): String {
        val script =
            generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "src/main/resources/db/assistant/V4__usage_tokens_by_model.sql") }
                .first { it.exists() }
                .readText()
        return script.substring(script.indexOf("INSERT INTO assistant.usage_daily_tokens")).trim().trimEnd(';')
    }
}
