package com.circaso.assistant.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * A user's tokens for one UTC day and one model (V4, decision 2026-11-08), split as the provider prices them, for the
 * staff cost report. [inputTokens] is the fresh input only; the day's activity (turns, voice, searches) stays on
 * [UsageDailyTable], one row a day whatever the models.
 */
object UsageDailyTokensTable : Table("assistant.usage_daily_tokens") {
    val userId = javaUUID("user_id")
    val usageDay = date("usage_day")
    val model = text("model").default(DEFAULT_MODEL)
    val inputTokens = long("input_tokens")
    val cacheReadTokens = long("cache_read_tokens").default(0)
    val cacheWriteTokens = long("cache_write_tokens").default(0)
    val outputTokens = long("output_tokens")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId, usageDay, model)

    init {
        index("usage_daily_tokens_day_idx", false, usageDay)
    }
}

/** What every day before V4 ran on, and what its backfilled rows say (Asha has been on Claude Haiku 5.5 since 2026-11-07). */
const val DEFAULT_MODEL = "claude-haiku-5-5"
