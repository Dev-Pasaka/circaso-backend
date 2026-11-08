package com.circaso.ops.presentation.mappers

import com.circaso.ops.domain.models.UsageBucket
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.shared.map
import io.ktor.server.application.ApplicationCall
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val DEFAULT_RANGE_DAYS = 30L
private const val DEFAULT_TOP_USERS = 10
private const val MAX_TOP_USERS = 50

/** `GET /v1/ops/ai-usage`'s query: whole UTC days `[from, until)`, the bucket and how many of the costliest users. */
data class AiUsageQuery(
    val from: LocalDate,
    val until: LocalDate,
    val bucket: UsageBucket,
    val limit: Int,
)

/**
 * `from` from the start of its day, `to` up to the end of its day (a date counts that whole day; an instant mid-day
 * counts its day too). Defaults: the last 30 days with today, by day, the 10 costliest users (at most 50).
 */
fun ApplicationCall.aiUsageQuery(): Resource<AiUsageQuery> =
    parseInstant(query("to"), "to", endOfDay = true).flatMap { to ->
        parseInstant(query("from"), "from").flatMap { from ->
            parseEnum<UsageBucket>(query("bucket"), "bucket").flatMap { bucket ->
                topLimit().map { limit ->
                    val until = to?.let(::dayEnd) ?: LocalDate.now(ZoneOffset.UTC).plusDays(1)
                    val start =
                        from?.let { LocalDate.ofInstant(it, ZoneOffset.UTC) } ?: until.minusDays(DEFAULT_RANGE_DAYS)
                    AiUsageQuery(start, until, bucket ?: UsageBucket.DAY, limit)
                }
            }
        }
    }

/** The first day not in the range: the instant's own day when it is exactly midnight, else the next. */
private fun dayEnd(instant: Instant): LocalDate {
    val day = LocalDate.ofInstant(instant, ZoneOffset.UTC)
    return if (day.atStartOfDay().toInstant(ZoneOffset.UTC) == instant) day else day.plusDays(1)
}

private fun ApplicationCall.topLimit(): Resource<Int> {
    val raw = query("limit") ?: return Resource.success(DEFAULT_TOP_USERS)
    val asked = raw.toIntOrNull()?.takeIf { it > 0 } ?: return malformed("limit", "must be a positive number")
    return Resource.success(asked.coerceAtMost(MAX_TOP_USERS))
}
