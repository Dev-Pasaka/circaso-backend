package com.circaso.assistant.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

/** A user's totals for one UTC day, for cost reports. The live counters are in Redis (`ast:quota:…`). */
object UsageDailyTable : Table("assistant.usage_daily") {
    val userId = javaUUID("user_id")
    val usageDay = date("usage_day")
    val textTurns = integer("text_turns")
    val voiceSeconds = integer("voice_seconds")
    val inputTokens = long("input_tokens")
    val outputTokens = long("output_tokens")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(userId, usageDay)
}
