package com.circaso.gateway

import com.circaso.shared.DomainError
import com.circaso.web.CallPrincipal
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/** How far a request got at the edge before its route. */
internal sealed interface Admission {
    /** Version, authentication or scope said no: nothing was counted or claimed. */
    class Refused(val error: DomainError) : Admission

    /**
     * The rate limit and the idempotency claim, both sent. [limited] is the rate limit's refusal, if any; [claim] is
     * null when the method isn't a state change.
     */
    class Counted internal constructor(
        val limited: DomainError?,
        val claim: Claim?,
        private val undoCount: suspend () -> Unit,
        private val releaseClaim: suspend () -> Unit,
    ) : Admission {
        /** Gives back a claim made alongside a refusal that comes before it (the rate limit). */
        suspend fun release() {
            if (claim?.claimed == true) releaseClaim()
        }

        /** Takes back both: for a request refused by authentication, which runs before the two. */
        suspend fun undo() {
            undoCount()
            release()
        }

        /** [release] on the way out of a failure: never throws over the failure, and runs even when cancelled. */
        suspend fun abandon() {
            withContext(NonCancellable) { runCatching { release() } }
        }
    }

    /** [key] is null when the client sent no `Idempotency-Key`; [claimed] is whether this request took it. */
    class Claim(val key: String?, val claimed: Boolean)
}

/**
 * The gateway's Redis steps for one request: the rate-limit counter and, for a state change, the idempotency
 * claim. Both are written to the shared connection before either reply is awaited, so they cost one round trip
 * (and, sent alongside the token's deny-list lookup, the same one as authentication).
 */
internal class Admissions(private val limiter: RateLimiter, val idempotency: Idempotency) {
    suspend fun count(
        call: ApplicationCall,
        path: String,
        method: String,
        principal: CallPrincipal?,
    ): Admission.Counted {
        val limit = RateLimitTable.limitFor(method, path, principal != null)
        val subject =
            when (limit?.by) {
                LimitBy.USER -> principal?.userId?.toString() ?: call.clientIp()
                LimitBy.IP, null -> call.clientIp()
            }
        val mutating = method in Idempotency.mutating
        val key =
            if (!mutating) {
                null
            } else {
                call.request
                    .header(Idempotency.HEADER)
                    ?.trim()
                    .orEmpty()
                    .takeIf { it.isNotEmpty() }
                    ?.let { Idempotency.redisKey(principal?.userId?.toString() ?: call.clientIp(), method, path, it) }
            }

        return coroutineScope {
            // UNDISPATCHED: the SET NX is written before the counter's script, without waiting for its reply.
            val claiming = key?.let { async(start = CoroutineStart.UNDISPATCHED) { idempotency.begin(it) } }
            val limited =
                try {
                    limit?.let { limiter.check(it, subject) }
                } catch (e: Throwable) {
                    // The counter failed: a claim already made would hold the key for a minute; give it back.
                    if (key != null && claiming != null) {
                        withContext(NonCancellable) {
                            if (runCatching { claiming.await() }.getOrDefault(false)) {
                                runCatching { idempotency.abandon(key) }
                            }
                        }
                    }
                    throw e
                }
            val claimed = claiming?.await() ?: false
            Admission.Counted(
                limited = limited,
                claim = if (mutating) Admission.Claim(key, claimed) else null,
                undoCount = { limit?.let { limiter.undo(it, subject) } },
                releaseClaim = { key?.let { idempotency.abandon(it) } },
            )
        }
    }
}
