package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.models.Presence
import com.circaso.messaging.domain.ports.PresenceStore
import com.circaso.messaging.domain.ports.PresenceWatchers
import com.circaso.messaging.domain.support.DeliveryNotifier
import com.circaso.messaging.domain.support.MessagingConfig
import java.time.Clock

/**
 * `messaging.presence-sweep`, every 5 seconds on one node: the phones that went quiet without saying `away` (killed,
 * out of signal). Each heartbeat scheduled a check for when its 75 seconds run out; a check whose heartbeat is still
 * the person's latest, and still `active`, means they just went offline, and their watchers hear it. A later
 * heartbeat or an `away` makes the check moot. Returns how many went offline, for the job's log line.
 */
class SweepPresence(
    private val store: PresenceStore,
    private val watchers: PresenceWatchers,
    private val notifier: DeliveryNotifier,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int {
        val now = clock.instant()
        val due = store.takeDue(now).groupBy({ it.userId }, { it.lastActiveAt }).mapValues { (_, ats) -> ats.max() }
        if (due.isEmpty()) return 0
        val records = store.read(due.keys)
        var offline = 0
        due.forEach { (userId, lastActiveAt) ->
            val record = records[userId] ?: return@forEach
            if (!record.active || record.lastActiveAt != lastActiveAt) return@forEach
            if (record.isOnline(now, config.presenceTtl)) return@forEach
            offline++
            val audience = watchers.watchersOf(userId, now)
            if (audience.isNotEmpty()) notifier.presence(audience, Presence(userId, false, record.lastActiveAt))
        }
        return offline
    }
}
