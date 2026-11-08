package com.circaso.ops.domain.support

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.AuditEntry
import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.ops.domain.repositories.AuditRepository
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import kotlinx.coroutines.CancellationException
import java.time.Clock

/**
 * Every staff action runs inside [record] (decision 2026-11-08: an audit log of every staff action, viewable by ADMIN).
 * The line is written `PENDING` before the action and finished after it, so no action can happen without its line:
 * an action whose audit line can't be written doesn't run at all, and one interrupted half way leaves `PENDING`, which
 * says exactly that. The action itself is another module's (identity, support) and has its own transaction; the two
 * are not one transaction, by the architecture's rule, and the order above is what makes that safe.
 */
class AuditTrail(private val audit: AuditRepository, private val clock: Clock) {
    suspend fun <T> record(
        actor: Actor,
        action: AuditAction,
        targetId: String?,
        reason: String? = null,
        detail: String? = null,
        /** What changed, from the result: replaces [detail] when the action succeeded (a rule's before and after). */
        outcome: (T) -> String? = { null },
        block: suspend () -> Resource<T>,
    ): Resource<T> {
        val entry =
            AuditEntry(
                id = Uuid7.generate(clock.instant()),
                actorId = actor.staffId,
                actorEmail = actor.email,
                actorRole = actor.role,
                action = action.wire,
                targetType = action.target.wire,
                targetId = targetId,
                reason = reason,
                detail = detail,
                outcome = AuditOutcome.PENDING,
                errorCode = null,
                requestId = actor.requestId,
                at = clock.instant(),
            )
        audit.begin(entry)
        val result =
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                audit.finish(entry.id, AuditOutcome.FAILED, "internal.error", clock.instant())
                throw e
            }
        when (result) {
            is Resource.Success -> {
                audit.finish(entry.id, AuditOutcome.SUCCEEDED, null, clock.instant(), outcome(result.data))
            }

            is Resource.Error -> {
                audit.finish(entry.id, AuditOutcome.FAILED, result.error.code, clock.instant())
            }
        }
        return result
    }
}
