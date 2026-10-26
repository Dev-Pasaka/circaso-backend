package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.ports.QuotaCounters
import com.circaso.assistant.domain.support.secondsUntilReset
import com.circaso.assistant.domain.support.today
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * A11, metering: voice seconds of a live conversation, both ways (the user's speech and Asha's playing time; never
 * silence), added to the day's voice counter as they pass. Answers the seconds left, or `assistant.quota_exceeded` once the allowance
 * is used up, which closes the socket. [dailyLimit] is the plan's, read once when the socket opened.
 */
class MeterLiveVoice(private val quota: QuotaCounters, private val clock: Clock) {
    suspend operator fun invoke(
        userId: UserId,
        seconds: Int,
        dailyLimit: Int,
    ): Resource<Long> {
        val day = clock.today()
        val before = quota.voiceSeconds(userId, day)
        // never past the cap: the seconds over it end the conversation instead of being charged
        val charged = seconds.coerceAtMost((dailyLimit - before).toInt()).coerceAtLeast(0)
        val used = if (charged > 0) quota.addVoiceSeconds(userId, day, charged) else before
        val left = dailyLimit - used
        if (left <= 0) return Resource.error(AssistantError.QuotaExceeded(clock.secondsUntilReset()))
        return Resource.success(left)
    }
}
