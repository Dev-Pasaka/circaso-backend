package com.circaso.assistant.presentation.mappers

import com.circaso.assistant.api.AshaUsageDay
import com.circaso.assistant.api.AshaUsageUser
import com.circaso.assistant.api.ModelTokenSums
import com.circaso.assistant.domain.models.TokenCounts
import com.circaso.assistant.domain.models.UsageDay
import com.circaso.assistant.domain.models.UsageUser

fun UsageDay.toApi() =
    AshaUsageDay(
        day = activity.day,
        textTurns = activity.textTurns,
        voiceSeconds = activity.voiceSeconds,
        webSearches = activity.webSearches,
        activeUsers = activity.users,
        tokens = tokens.toApi(),
    )

fun UsageUser.toApi() =
    AshaUsageUser(
        userId = activity.userId,
        textTurns = activity.textTurns,
        voiceSeconds = activity.voiceSeconds,
        webSearches = activity.webSearches,
        tokens = tokens.toApi(),
    )

private fun Map<String, TokenCounts>.toApi(): List<ModelTokenSums> =
    entries
        .sortedBy { it.key }
        .map { (model, t) -> ModelTokenSums(model, t.input, t.cacheRead, t.cacheWrite, t.output) }
