package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.support.DailyUsage
import com.circaso.shared.UserId

/**
 * A11, when a live conversation ends: the day's totals go to `usage_daily` once, after the last seconds are metered,
 * rather than on every metering tick.
 */
class SaveLiveVoiceUsage(private val usage: DailyUsage) {
    suspend operator fun invoke(userId: UserId) = usage.save(userId)
}
