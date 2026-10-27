package com.circaso.identity.data

import com.circaso.identity.MemoryCache
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.identity.api.SessionId
import com.circaso.identity.data.PostgresIdentity.Companion.count
import com.circaso.identity.data.security.KeyedPhoneProtection
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.ports.PhoneRelease
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.support.IdentityConfig
import com.circaso.identity.domain.support.PhoneOwnership
import com.circaso.infrastructure.api.Secrets
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.shared.decode
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * I16's take-over on PostgreSQL: once the release notice has run out, the requester's verification takes the number
 * from the abandoned account, which is closed like any deletion (I14), all in one transaction.
 */
class PhoneTakeOverTest {
    private val pg = PostgresIdentity()
    private val cache = MemoryCache()
    private val clock = Clock.systemUTC()
    private val config = IdentityConfig()
    private val phones =
        KeyedPhoneProtection(
            object : Secrets {
                override fun get(name: String): String? = null
            },
            local = true,
        )
    private val number = PhoneNumber.parse("+254712345678")!!
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeTest
    fun clean() = PostgresIdentity.reset()

    private fun ownership(accounts: AccountRepository = pg.accounts) =
        PhoneOwnership(pg.transactor, accounts, pg.sessions, pg.store, cache, phones, pg.bus, config, clock)

    /** An active, unverified account holding the number, with a live session, and a pending one that asked for it. */
    private suspend fun abandonedAndRequester(): Triple<UserId, Account, SessionId> {
        val holder = Account(UserId.new(), null, AccountStatus.PENDING_PHONE, null, Verification.NONE, 1, now)
        val requester = holder.copy(userId = UserId.new())
        val session =
            Session(SessionId.new(), holder.userId, DeviceId.new(), "h", Scope.APP, SessionStatus.ACTIVE, now, now)
        pg.transactor.run("test.setup") {
            pg.accounts.create(holder)
            pg.accounts.create(requester)
            check(pg.accounts.claimPhone(pg.accounts.lock(holder.userId)!!, number))
            val claimed = pg.accounts.lock(holder.userId)!!
            pg.store.write(
                Changes(accounts = listOf(claimed.copy(status = AccountStatus.ACTIVE)), sessions = listOf(session)),
            )
        }
        val due = clock.millis() - 1_000
        cache.savePhoneRelease(phones.hmac(number), PhoneRelease(holder.userId, requester.userId, due))
        return Triple(holder.userId, pg.accounts.find(requester.userId)!!, session.sessionId)
    }

    @Test
    fun `the requester gets the number and the abandoned account is closed like a deletion`() =
        runBlocking<Unit> {
            val (holderId, requester, sessionId) = abandonedAndRequester()

            val result = ownership().confirm(requester, number, DeviceId.new())

            assertTrue(result is Resource.Success)
            assertEquals(requester.userId, pg.accounts.findByPhone(number)!!.userId)
            val holder = pg.accounts.find(holderId)!!
            assertEquals(AccountStatus.DELETED, holder.status)
            assertNull(holder.phone)
            val deleteAfter = assertNotNull(holder.deleteAfter)
            assertTrue(Duration.between(now.plus(config.deletionGrace), deleteAfter).abs() < Duration.ofMinutes(1))
            assertEquals(SessionStatus.REVOKED, pg.sessions.find(sessionId)!!.status)
            assertTrue(sessionId in cache.revoked, "the gateway denies its tokens")
            assertEquals(1, count("SELECT count(*) FROM identity.outbox WHERE type = '${AccountStatusChanged.TYPE}'"))
            assertEquals(
                AccountStatusChanged(holderId, AccountStatus.DELETED),
                pg.bus.published
                    .single()
                    .decode<AccountStatusChanged>(),
            )
        }

    @Test
    fun `a lost claim rolls back the closing of the abandoned account too`() =
        runBlocking<Unit> {
            val (holderId, requester, sessionId) = abandonedAndRequester()
            // Another account got the number in the moment between the release and the claim.
            val losing =
                object : AccountRepository by pg.accounts {
                    override suspend fun claimPhone(account: Account, phone: PhoneNumber) = false
                }

            val result = ownership(losing).confirm(requester, number, DeviceId.new())

            assertEquals("identity.phone_taken", (result as Resource.Error).error.code)
            val holder = pg.accounts.find(holderId)!!
            assertEquals(AccountStatus.ACTIVE, holder.status)
            assertEquals(number, holder.phone)
            assertNull(holder.deleteAfter)
            assertEquals(SessionStatus.ACTIVE, pg.sessions.find(sessionId)!!.status)
            assertEquals(0, count("SELECT count(*) FROM identity.outbox"))
            assertTrue(pg.bus.published.isEmpty())
        }
}
