package com.circaso.identity.data.repositories

import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.infrastructure.api.Database
import com.circaso.shared.EventBus

/**
 * Writes one operation's rows and its outbox rows in the transaction that is open, so all of them land or none does;
 * the events go on the stream only after the commit ([publish]). Accounts are written first, so a lost number claim
 * fails the operation before anything else is sent.
 */
class IdentityStoreImpl(
    private val database: Database,
    private val accounts: AccountRepositoryImpl,
    private val devices: DeviceRepositoryImpl,
    private val sessions: SessionRepositoryImpl,
    private val providers: ProviderRepositoryImpl,
    private val log: SecurityLogRepositoryImpl,
    private val recoveries: RecoveryRepositoryImpl,
    private val bus: EventBus,
) : IdentityStore {
    override suspend fun write(changes: Changes) {
        database.tx("identity.write") {
            changes.accounts.forEach { accounts.update(it) }
            changes.devices.forEach { devices.save(it) }
            changes.sessions.forEach { sessions.save(it) }
            changes.removedProviderLinks.forEach { providers.remove(it) }
            changes.securityEvents.forEach { log.add(it) }
            changes.recoveries.forEach { recoveries.save(it) }
            changes.events.forEach { database.writeOutbox(it) }
        }
    }

    override suspend fun publish(changes: Changes) {
        changes.events.forEach { bus.publish(it) }
    }

    override suspend fun commit(changes: Changes) {
        write(changes)
        publish(changes)
    }
}
