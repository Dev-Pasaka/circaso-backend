package com.circaso.identity.data

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.data.PostgresIdentity.Companion.count
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.PinVault
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.repositories.Changes
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `identity.pin_vaults` (V5) through the store: one row per account, replaced in place, deleted on request. */
class VaultDataTest {
    private val pg = PostgresIdentity()
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeTest
    fun clean() = PostgresIdentity.reset()

    private suspend fun account(): UserId {
        val userId = UserId.new()
        pg.transactor.run("test.create") {
            pg.accounts.create(Account(userId, null, AccountStatus.ACTIVE, null, Verification.NONE, 1, now))
        }
        return userId
    }

    private fun vault(
        userId: UserId,
        version: Int,
        at: Instant,
    ) = PinVault(
        userId,
        "AQID",
        "c2FsdHNhbHRzYWx0c2FsdA==",
        "Y2lwaGVy$version",
        "prf-hkdf-sha256+aes-256-gcm/v1",
        version,
        at,
    )

    private suspend fun write(changes: Changes) = pg.transactor.run("test.write") { pg.store.write(changes) }

    /** 1 when the row was first saved before it was last replaced. */
    private fun keptCreatedAt(userId: UserId): Int =
        count("SELECT count(*) FROM identity.pin_vaults WHERE user_id = '$userId' AND created_at < updated_at")

    @Test
    fun `a replace writes over the one row and keeps when it was first saved`() =
        runBlocking<Unit> {
            val userId = account()
            val other = account()
            write(Changes(vaults = listOf(vault(userId, 1, now), vault(other, 1, now))))

            val later = now.plus(Duration.ofMinutes(5))
            write(Changes(vaults = listOf(vault(userId, 2, later))))

            assertEquals(vault(userId, 2, later), pg.vaults.find(userId))
            assertEquals(vault(other, 1, now), pg.vaults.find(other), "another account's vault is untouched")
            assertEquals(1, count("SELECT count(*) FROM identity.pin_vaults WHERE user_id = '$userId'"))
            assertEquals(1, keptCreatedAt(userId), "created_at stays the first save's")
        }

    @Test
    fun `a removed vault is gone, and removing none changes nothing`() =
        runBlocking<Unit> {
            val userId = account()
            val other = account()
            write(Changes(vaults = listOf(vault(userId, 1, now), vault(other, 1, now))))

            write(Changes(removedVaults = listOf(userId)))
            write(Changes(removedVaults = listOf(userId)))

            assertNull(pg.vaults.find(userId))
            assertEquals(vault(other, 1, now), pg.vaults.find(other))
        }
}
