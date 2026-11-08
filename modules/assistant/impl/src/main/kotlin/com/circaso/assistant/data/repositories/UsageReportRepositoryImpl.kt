package com.circaso.assistant.data.repositories

import com.circaso.assistant.api.UsageRates
import com.circaso.assistant.data.tables.UsageDailyTable
import com.circaso.assistant.data.tables.UsageDailyTokensTable
import com.circaso.assistant.domain.models.DayActivity
import com.circaso.assistant.domain.models.TokenCounts
import com.circaso.assistant.domain.models.UserActivity
import com.circaso.assistant.domain.repositories.UsageReportRepository
import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.DecimalColumnType
import org.jetbrains.exposed.v1.core.Expression
import org.jetbrains.exposed.v1.core.ExpressionWithColumnType
import org.jetbrains.exposed.v1.core.IColumnType
import org.jetbrains.exposed.v1.core.LongColumnType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryBuilder
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.castTo
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.countDistinct
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.sum
import org.jetbrains.exposed.v1.javatime.JavaLocalDateColumnType
import org.jetbrains.exposed.v1.r2dbc.select
import java.math.BigDecimal
import java.time.LocalDate

/**
 * The staff cost report's reads (decision 2026-11-08). Every one is a range of a `usage_day` index of V4
 * (`usage_daily_day_idx`, `usage_daily_tokens_day_idx`) bounded by the caller's 366 days, or the primary key for a
 * page of users; all sums are done by PostgreSQL.
 */
class UsageReportRepositoryImpl(private val database: Database) : UsageReportRepository {
    override suspend fun activityByDay(from: LocalDate, until: LocalDate): List<DayActivity> =
        database.tx("usage.report-activity-by-day") {
            val turns = UsageDailyTable.textTurns.asLong().sum()
            val voice = UsageDailyTable.voiceSeconds.asLong().sum()
            val searches = UsageDailyTable.webSearches.asLong().sum()
            val users = UsageDailyTable.userId.count()
            UsageDailyTable
                .select(UsageDailyTable.usageDay, turns, voice, searches, users)
                .where { daysOf(UsageDailyTable.usageDay, from, until) }
                .groupBy(UsageDailyTable.usageDay)
                .orderBy(UsageDailyTable.usageDay to SortOrder.ASC)
                .map {
                    DayActivity(
                        day = it[UsageDailyTable.usageDay],
                        textTurns = it[turns] ?: 0,
                        voiceSeconds = it[voice] ?: 0,
                        webSearches = it[searches] ?: 0,
                        users = it[users],
                    )
                }.toList()
        }

    override suspend fun tokensByDay(from: LocalDate, until: LocalDate): Map<LocalDate, Map<String, TokenCounts>> =
        database.tx("usage.report-tokens-by-day") {
            val sums = TokenSums()
            UsageDailyTokensTable
                .select(listOf(UsageDailyTokensTable.usageDay, UsageDailyTokensTable.model) + sums.expressions)
                .where { daysOf(UsageDailyTokensTable.usageDay, from, until) }
                .groupBy(UsageDailyTokensTable.usageDay, UsageDailyTokensTable.model)
                .map { Triple(it[UsageDailyTokensTable.usageDay], it[UsageDailyTokensTable.model], sums.read(it)) }
                .toList()
                .groupBy({ it.first }, { it.second to it.third })
                .mapValues { (_, models) -> models.toMap() }
        }

    override suspend fun users(from: LocalDate, until: LocalDate): Long =
        database.tx("usage.report-users") {
            val users = UsageDailyTable.userId.countDistinct()
            UsageDailyTable
                .select(users)
                .where { daysOf(UsageDailyTable.usageDay, from, until) }
                .map { it[users] }
                .single()
        }

    override suspend fun heaviestUsers(
        from: LocalDate,
        until: LocalDate,
        rates: UsageRates,
        limit: Int,
    ): List<UserId> =
        database.tx("usage.report-heaviest-users") {
            val weight = UsageWeight(rates, from, until)
            UsageDailyTokensTable
                .select(UsageDailyTokensTable.userId, weight)
                .where { daysOf(UsageDailyTokensTable.usageDay, from, until) }
                .groupBy(UsageDailyTokensTable.userId)
                .orderBy(weight to SortOrder.DESC, UsageDailyTokensTable.userId to SortOrder.ASC)
                .limit(limit)
                .map { UserId(it[UsageDailyTokensTable.userId]) }
                .toList()
        }

    override suspend fun activityOf(
        users: Set<UserId>,
        from: LocalDate,
        until: LocalDate,
    ): Map<UserId, UserActivity> {
        if (users.isEmpty()) return emptyMap()
        return database.tx("usage.report-activity-of") {
            val turns = UsageDailyTable.textTurns.asLong().sum()
            val voice = UsageDailyTable.voiceSeconds.asLong().sum()
            val searches = UsageDailyTable.webSearches.asLong().sum()
            UsageDailyTable
                .select(UsageDailyTable.userId, turns, voice, searches)
                .where {
                    (UsageDailyTable.userId inList users.map { it.value }) and
                        daysOf(UsageDailyTable.usageDay, from, until)
                }.groupBy(UsageDailyTable.userId)
                .map {
                    val userId = UserId(it[UsageDailyTable.userId])
                    userId to UserActivity(userId, it[turns] ?: 0, it[voice] ?: 0, it[searches] ?: 0)
                }.toList()
                .toMap()
        }
    }

    override suspend fun tokensOf(
        users: Set<UserId>,
        from: LocalDate,
        until: LocalDate,
    ): Map<UserId, Map<String, TokenCounts>> {
        if (users.isEmpty()) return emptyMap()
        return database.tx("usage.report-tokens-of") {
            val sums = TokenSums()
            UsageDailyTokensTable
                .select(listOf(UsageDailyTokensTable.userId, UsageDailyTokensTable.model) + sums.expressions)
                .where {
                    (UsageDailyTokensTable.userId inList users.map { it.value }) and
                        daysOf(UsageDailyTokensTable.usageDay, from, until)
                }.groupBy(UsageDailyTokensTable.userId, UsageDailyTokensTable.model)
                .map {
                    Triple(
                        UserId(it[UsageDailyTokensTable.userId]),
                        it[UsageDailyTokensTable.model],
                        sums.read(it),
                    )
                }.toList()
                .groupBy({ it.first }, { it.second to it.third })
                .mapValues { (_, models) -> models.toMap() }
        }
    }

    private fun daysOf(
        column: Column<LocalDate>,
        from: LocalDate,
        until: LocalDate,
    ): Op<Boolean> = (column greaterEq from) and (column less until)

    private fun Column<Int>.asLong(): ExpressionWithColumnType<Long> = castTo(LongColumnType())

    /** The four token sums of a grouped `usage_daily_tokens` read. */
    private class TokenSums {
        val input = UsageDailyTokensTable.inputTokens.sum()
        val cacheRead = UsageDailyTokensTable.cacheReadTokens.sum()
        val cacheWrite = UsageDailyTokensTable.cacheWriteTokens.sum()
        val output = UsageDailyTokensTable.outputTokens.sum()
        val expressions = listOf(input, cacheRead, cacheWrite, output)

        fun read(row: ResultRow) =
            TokenCounts(row[input] ?: 0, row[cacheRead] ?: 0, row[cacheWrite] ?: 0, row[output] ?: 0)
    }
}

/**
 * What one user's use over `[from, until)` weighs at [rates], for ordering the heaviest users, grouped by user over
 * `usage_daily_tokens`:
 *
 * `COALESCE(SUM(CASE WHEN model = ? THEN input_tokens * r + cache_read_tokens * r + … ELSE 0 END), 0)
 *  + COALESCE((SELECT SUM(web_searches) FROM assistant.usage_daily d WHERE d.user_id = <the user> AND <the range>), 0) * r`
 *
 * Model names and days are bound arguments; the rates are the server's own configured decimals (`BigDecimal`, written
 * with [BigDecimal.toPlainString]: digits, a point and a sign only), never a caller's text. A user's web searches count
 * only when they used tokens in the range too, which every search does: it runs inside one of Asha's turns.
 */
private class UsageWeight(private val rates: UsageRates, private val from: LocalDate, private val until: LocalDate) :
    ExpressionWithColumnType<BigDecimal>() {
    override val columnType: IColumnType<BigDecimal> = DecimalColumnType(PRECISION, SCALE)

    override fun toQueryBuilder(queryBuilder: QueryBuilder) {
        val t = UsageDailyTokensTable
        val d = UsageDailyTable
        queryBuilder.sql("(COALESCE(SUM(")
        if (rates.byModel.isEmpty()) {
            queryBuilder.sql("0")
        } else {
            queryBuilder.sql("CASE")
            for ((model, rate) in rates.byModel) {
                queryBuilder.sql(" WHEN ", t.model, " = ")
                queryBuilder.registerArgument(TextColumnType(), model)
                queryBuilder.sql(" THEN ", t.inputTokens, " * ", rate.input.toPlainString())
                queryBuilder.sql(" + ", t.cacheReadTokens, " * ", rate.cacheRead.toPlainString())
                queryBuilder.sql(" + ", t.cacheWriteTokens, " * ", rate.cacheWrite.toPlainString())
                queryBuilder.sql(" + ", t.outputTokens, " * ", rate.output.toPlainString())
            }
            queryBuilder.sql(" ELSE 0 END")
        }
        queryBuilder.sql("), 0) + COALESCE((SELECT SUM(", d.webSearches, ") FROM ", d.tableName)
        queryBuilder.sql(" WHERE ", d.userId, " = ", t.userId, " AND ", d.usageDay, " >= ")
        queryBuilder.registerArgument(JavaLocalDateColumnType(), from)
        queryBuilder.sql(" AND ", d.usageDay, " < ")
        queryBuilder.registerArgument(JavaLocalDateColumnType(), until)
        queryBuilder.sql("), 0) * ", rates.perWebSearch.toPlainString(), ")")
    }

    /** Appends text and expressions in order: text as written, an expression as the DSL renders it. */
    private fun QueryBuilder.sql(vararg parts: Any) {
        for (part in parts) {
            when (part) {
                is Expression<*> -> append(part)
                else -> append(part.toString())
            }
        }
    }

    private companion object {
        const val PRECISION = 38
        const val SCALE = 12
    }
}
