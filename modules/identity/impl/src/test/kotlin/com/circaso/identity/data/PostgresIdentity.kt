package com.circaso.identity.data

import com.circaso.identity.IdentityPorts
import com.circaso.identity.MemoryBus
import com.circaso.identity.data.repositories.AccountRepositoryImpl
import com.circaso.identity.data.repositories.DatabaseTransactor
import com.circaso.identity.data.repositories.DeletionFeedbackRepositoryImpl
import com.circaso.identity.data.repositories.DeviceRepositoryImpl
import com.circaso.identity.data.repositories.ExpiredRows
import com.circaso.identity.data.repositories.IdentityStoreImpl
import com.circaso.identity.data.repositories.KeyBackupRepositoryImpl
import com.circaso.identity.data.repositories.ProviderRepositoryImpl
import com.circaso.identity.data.repositories.RecoveryRepositoryImpl
import com.circaso.identity.data.repositories.SecurityLogRepositoryImpl
import com.circaso.identity.data.repositories.SessionRepositoryImpl
import com.circaso.identity.data.repositories.VaultRepositoryImpl
import com.circaso.identity.di.identityDatabaseSpec
import com.circaso.infrastructure.testing.TestPostgres
import java.time.Clock

/**
 * Identity's data layer on infrastructure's test PostgreSQL, migrated by identity's own Flyway scripts, as the app
 * wires it. [reset] empties every identity table (the outbox's partitions stay).
 */
class PostgresIdentity(val clock: Clock = Clock.systemUTC()) {
    val bus = MemoryBus()
    val database = TestPostgres.database(identityDatabaseSpec())
    val transactor = DatabaseTransactor(database)
    val accounts = AccountRepositoryImpl(database, clock)
    val providers = ProviderRepositoryImpl(database)
    val devices = DeviceRepositoryImpl(database)
    val sessions = SessionRepositoryImpl(database, clock)
    val log = SecurityLogRepositoryImpl(database, clock)
    val recoveries = RecoveryRepositoryImpl(database)
    val vaults = VaultRepositoryImpl(database)
    val keyBackups = KeyBackupRepositoryImpl(database)
    val feedback = DeletionFeedbackRepositoryImpl(database)
    val store =
        IdentityStoreImpl(
            database,
            accounts,
            devices,
            sessions,
            providers,
            log,
            recoveries,
            vaults,
            keyBackups,
            feedback,
            bus,
        )
    val expired = ExpiredRows(database)

    fun ports() = IdentityPorts(transactor, accounts, providers, devices, sessions, store, vaults, keyBackups)

    companion object {
        fun reset() = TestPostgres.truncate("identity")

        /** A single number from a query run as the superuser, for looking at what the code under test wrote. */
        fun count(sql: String): Int =
            TestPostgres.superuser { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(sql).use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
    }
}
