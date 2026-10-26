package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.support.secondsUntilReset
import com.circaso.assistant.domain.support.today
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * A11, metering: voice seconds of a live conversation, both ways (what the phone streamed and what Asha said), added
 * to the day's voice counter as they pass. Answers the seconds left, or `assistant.quota_exceeded` once the allowance
 * is used up, which closes the socket. [dailyLimit] is the plan's, read once when the socket opened.
 */
class MeterLiveVoice(private val quota: QuotaCounters, private val clock: Clock) {
    suspend operator fun invoke(
        userId: UserId,
        seconds: Int,
        dailyLimit: Int,
    ): Resource<Long> {
        val day = clock.today()
        val used = if (seconds > 0) quota.addVoiceSeconds(userId, day, seconds) else quota.voiceSeconds(userId, day)
        val left = dailyLimit - used
        if (left <= 0) return Resource.error(AssistantError.QuotaExceeded(clock.secondsUntilReset()))
        return Resource.success(left)
    }
}
