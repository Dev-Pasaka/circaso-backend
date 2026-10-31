package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.models.Presence
import com.circaso.messaging.domain.models.PresenceRecord
import com.circaso.messaging.domain.ports.PresenceStore
import com.circaso.messaging.domain.ports.PresenceWatchers
import com.circaso.messaging.domain.support.DeliveryNotifier
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock
import java.time.temporal.ChronoUnit

/**
 * `messaging.presence` from the phone: `active` when the app comes to the foreground and every 30 seconds there,
 * `away` 15 seconds after it leaves. Each `active` moves the last seen on and schedules a look once its 75 seconds
 * run out ([SweepPresence]); only a change between online and offline reaches the people watching. An `away` from
 * someone whose heartbeat already lapsed is still announced, in case the sweep hasn't run yet: a second offline frame
 * changes nothing on the phone.
 */
class UpdatePresence(
    private val store: PresenceStore,
    private val watchers: PresenceWatchers,
    private val notifier: DeliveryNotifier,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(userId: UserId, active: Boolean): Resource<Unit> {
        val now = clock.instant().truncatedTo(ChronoUnit.MILLIS)
        val old = store.read(listOf(userId))[userId]
        val wasOnline = old?.isOnline(now, config.presenceTtl) == true
        val changed =
            if (active) {
                store.save(userId, PresenceRecord(true, now))
                store.scheduleCheck(userId, now, now.plus(config.presenceTtl))
                if (wasOnline) null else Presence(userId, true, now)
            } else {
                if (old == null || !old.active) return Resource.success(Unit)
                val lastSeen = if (wasOnline) now else old.lastActiveAt
                store.save(userId, PresenceRecord(false, lastSeen))
                Presence(userId, false, lastSeen)
            }
        if (changed != null) {
            val audience = watchers.watchersOf(userId, now)
            if (audience.isNotEmpty()) notifier.presence(audience, changed)
        }
        return Resource.success(Unit)
    }
}
