package com.circaso.ramp.domain.support

import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailGateway
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.OnlineSessionCopy
import com.circaso.ramp.domain.models.RestoreOutcome
import com.circaso.ramp.domain.models.SessionTally
import com.circaso.ramp.domain.models.StoredSession
import com.circaso.ramp.domain.ports.OnlineSessionCopies
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.repositories.MerchantRepository
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Puts a merchant back online after a restart from their session's encrypted working copy (decision 2026-11-24), so a
 * deploy takes nobody offline. **The database row is the truth**: a copy loads only when the row still shows the very
 * session it was written for (same `online_since`, not ended); a copy for a session the row no longer shows is
 * deleted and loads nothing. A copy that doesn't open is deleted too, and the session then ends with `RESTART`, as it
 * does when there is no copy at all (Redis lost it, its expiry passed).
 *
 * Idempotent and safe against a go-offline at the same moment: restores run one at a time on this node, keys already
 * held are never replaced, and the row is read again once the keys are in memory; if the session ended meanwhile, the
 * keys go again. A copy newer than the row is a go-online in flight (it writes the copy just before the row) and is
 * left alone for [IN_FLIGHT].
 */
class SessionRestorer(
    private val merchants: MerchantRepository,
    private val sessions: TradingSessions,
    private val copies: OnlineSessionCopies,
    private val email: EmailGateway,
    private val clock: Clock,
) {
    private val lock = Mutex()

    /**
     * Every merchant whose row shows a session: one whose keys this node holds has its open-ended mark renewed; any
     * other is restored, or, with no usable copy, ended with `RESTART` (guarded by `online_since`) and emailed once.
     */
    // TODO(decision: more than one app node; `rmp:work` would route to the holder, and this check would read the mark)
    suspend fun settle(now: Instant): SessionTally {
        var tally = SessionTally()
        merchants.withSessions(MAX_SESSIONS).forEach { merchant ->
            val merchantId = merchant.merchantId
            if (sessions.of(merchantId) != null) {
                if (merchant.onlineOpenEnded) sessions.renew(merchantId)
                return@forEach
            }
            val lost =
                when (restore(merchantId)) {
                    RestoreOutcome.RESTORED -> {
                        tally += SessionTally(restored = 1)
                        return@forEach
                    }

                    RestoreOutcome.HELD, RestoreOutcome.PENDING, RestoreOutcome.STALE -> {
                        return@forEach
                    }

                    RestoreOutcome.MISSING -> {
                        SessionTally(missing = 1)
                    }

                    RestoreOutcome.UNREADABLE -> {
                        SessionTally(unreadable = 1)
                    }
                }
            // Guarded by when the session began: one opened since this run read the row is left alone. Its stale mark
            // in Redis isn't touched either (it may already be the new session's) and expires on its own.
            if (!merchants.endLostSession(merchantId, merchant.onlineSince, now)) return@forEach
            tally += lost
            tellTakenOffline(merchant, now)
        }
        return tally
    }

    /** Best effort: an email that can't be sent (no template id yet, the provider down) changes nothing. */
    private suspend fun tellTakenOffline(merchant: Merchant, now: Instant) {
        val session = (merchant.onlineSince ?: now).toEpochMilli()
        email.send(
            merchant.email,
            Email.MerchantTakenOffline,
            idempotencyKey = "merchant-offline-${merchant.merchantId}-$session",
        )
    }

    suspend fun restore(merchantId: MerchantId): RestoreOutcome =
        lock.withLock {
            if (sessions.of(merchantId) != null) return@withLock RestoreOutcome.HELD
            when (val stored = copies.load(merchantId)) {
                StoredSession.Missing -> {
                    RestoreOutcome.MISSING
                }

                StoredSession.Unreadable -> {
                    copies.delete(merchantId)
                    RestoreOutcome.UNREADABLE
                }

                is StoredSession.Found -> {
                    restore(stored.copy)
                }
            }
        }

    private suspend fun restore(copy: OnlineSessionCopy): RestoreOutcome {
        val merchantId = copy.merchantId
        val row = merchants.find(merchantId)
        if (row == null) {
            copies.delete(merchantId)
            return RestoreOutcome.STALE
        }
        if (!matches(row, copy)) {
            if (inFlight(row, copy)) return RestoreOutcome.PENDING
            copies.delete(merchantId)
            return if (row.isOnlineAt(clock.instant())) RestoreOutcome.MISSING else RestoreOutcome.STALE
        }
        if (!sessions.adopt(merchantId, copy.keys, row.onlineUntil)) return RestoreOutcome.HELD
        // Going offline may have ended the session between the read above and the keys landing here.
        val after = merchants.find(merchantId)
        if (after == null || !matches(after, copy)) {
            sessions.release(merchantId, copy.keys)
            return RestoreOutcome.STALE
        }
        return RestoreOutcome.RESTORED
    }

    /** The row still shows the copy's session: not ended, and begun at the same instant. */
    private fun matches(row: Merchant, copy: OnlineSessionCopy): Boolean =
        row.isOnlineAt(clock.instant()) && row.onlineSince == copy.since

    /** A copy written after anything the row records, a moment ago: the go-online that wrote it hasn't reached the row. */
    private fun inFlight(row: Merchant, copy: OnlineSessionCopy): Boolean {
        val latest = listOfNotNull(row.onlineSince, row.offlineAt).maxOrNull() ?: Instant.EPOCH
        return copy.since.isAfter(latest) && copy.since.plus(IN_FLIGHT).isAfter(clock.instant())
    }

    companion object {
        /** How long a copy newer than its row is taken for a go-online still in flight. */
        val IN_FLIGHT: Duration = Duration.ofMinutes(2)
        private const val MAX_SESSIONS = 500
    }
}
