package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.errors.AssistantError
import com.circaso.assistant.domain.ports.AshaHealth
import com.circaso.assistant.domain.ports.HealthLog
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.CancellationException

/**
 * What a failed turn means to the person (decision 2026-11-07). While Asha is down ([AshaHealth]), a turn her model
 * couldn't answer is [AssistantError.Unavailable], down for maintenance, and the person is recorded (their id only) to
 * hear once that she is back. Any other failure is passed on as it was.
 */
class AshaOutage(private val health: AshaHealth, private val log: HealthLog = HealthLog.None) {
    suspend fun failure(userId: UserId, failed: Resource.Error): Resource.Error {
        if (failed.error !== AssistantError.ModelUnavailable) return failed
        return try {
            if (health.status().isAvailable) {
                failed
            } else {
                health.recordAffected(userId)
                Resource.Error(AssistantError.Unavailable, failed.cause)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.storeFailed(e.javaClass.simpleName)
            failed
        }
    }
}
