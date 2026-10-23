package com.circaso.identity

import com.circaso.identity.api.AccountActivated
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.SessionId
import com.circaso.identity.data.entities.AccountRow
import com.circaso.identity.data.entities.DeviceRow
import com.circaso.identity.data.entities.IdentityOutboxRow
import com.circaso.identity.data.entities.PhoneClaimRow
import com.circaso.identity.data.entities.ProviderClaimRow
import com.circaso.identity.data.entities.ProviderLinkRow
import com.circaso.identity.data.entities.RecoveryByRequestRow
import com.circaso.identity.data.entities.SecurityEventRow
import com.circaso.identity.data.entities.SessionRow
import com.circaso.identity.data.entities.SupportRecoveryRow
import com.circaso.identity.data.migrations.V001_CreateIdentityTables
import com.circaso.identity.data.repositories.AccountRepositoryImpl
import com.circaso.identity.data.repositories.DeviceRepositoryImpl
import com.circaso.identity.data.repositories.IdentityOutboxStore
import com.circaso.identity.data.repositories.IdentityStoreImpl
import com.circaso.identity.data.repositories.ProviderRepositoryImpl
import com.circaso.identity.data.repositories.RecoveryRepositoryImpl
import com.circaso.identity.data.repositories.SecurityLogRepositoryImpl
import com.circaso.identity.data.repositories.SessionRepositoryImpl
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.RecoveryStatus
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.Session
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.models.SupportRecovery
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.repositories.Changes
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.shared.envelope
import io.kandra.migrate.KandraMigrationRunner
import io.kandra.test.KandraTestBackend
import io.kandra.test.KandraTestUtils
import io.kandra.test.KandraTestcontainers
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The data layer against a real Cassandra-compatible container: the migration, the batch with its outbox, claims, paging. */
class DataLayerTest {

    private val db: io.kandra.test.KandraRuntimeHandle = run {
        KandraTestBackend.newSession().use { admin ->
            admin.execute(KandraTestBackend.createKeyspaceCql("circaso_identity"))
            KandraMigrationRunner(admin, keyspace = "circaso_identity").run(V001_CreateIdentityTables)
        }
        KandraTestcontainers.freshKeyspace(
            AccountRow::class, PhoneClaimRow::class, ProviderClaimRow::class, ProviderLinkRow::class, DeviceRow::class,
            SessionRow::class, SecurityEventRow::class, SupportRecoveryRow::class, RecoveryByRequestRow::class, IdentityOutboxRow::class,
        )
    }

    private val bus = MemoryBus()
    private val accounts = AccountRepositoryImpl(db.suspendRepository(), db.suspendRepository())
    private val providers = ProviderRepositoryImpl(db.suspendRepository(), db.suspendRepository())
    private val devices = DeviceRepositoryImpl(db.suspendRepository())
    private val sessions = SessionRepositoryImpl(db.suspendRepository())
    private val log = SecurityLogRepositoryImpl(db.suspendRepository())
    private val recoveries = RecoveryRepositoryImpl(db.suspendRepository(), db.suspendRepository())
    private val outbox = IdentityOutboxStore(db.suspendRepository())
    private val store = IdentityStoreImpl(
        db.runtime, db.suspendRepository(), db.suspendRepository(), db.suspendRepository(), db.suspendRepository(),
        db.suspendRepository(), db.suspendRepository(), db.suspendRepository(), db.suspendRepository(), db.suspendRepository(), bus,
    )

    @AfterTest
    fun close() {
        db.close()
        KandraTestUtils.reset()
    }

    private val now = Instant.now()
    private fun account(user: UserId, phone: String? = "+254712345678") = Account(
        user, phone?.let(PhoneNumber::parse), AccountStatus.ACTIVE, null, Verification.NONE, 1, now,
    )

    @Test
    fun `one commit writes every table and the outbox, then publishes the event`() = runBlocking {
        val user = UserId.new()
        val device = Device(user, DeviceId.new(), "android", "Pixel", "1.0.0", "key", "ES256", "fcm:t", DeviceStatus.ACTIVE, now, now)
        val session = Session(SessionId.new(), user, device.deviceId, "hash", Scope.APP, SessionStatus.ACTIVE, now, now)
        val event = bus.envelope(AccountActivated(user, "hmac"))

        store.commit(
            Changes(
                accounts = listOf(account(user).copy(activeDeviceId = device.deviceId)),
                devices = listOf(device),
                sessions = listOf(session),
                providerLinks = listOf(ProviderLink(user, SocialProvider.GOOGLE, "sub-1", "a@example.com", now)),
                securityEvents = listOf(SecurityEvent(user, null, SecurityEventType.SIGN_IN, "Pixel", null)),
                events = listOf(event),
            ),
        )

        assertEquals(AccountStatus.ACTIVE, accounts.find(user)?.status)
        assertEquals("+254 7•• ••• 678", accounts.find(user)?.phone?.masked())
        assertEquals("Pixel", devices.find(user, device.deviceId)?.model)
        assertEquals("fcm:t", devices.find(user, device.deviceId)?.pushToken)
        assertEquals(SessionStatus.ACTIVE, sessions.find(session.sessionId)?.status)
        assertEquals(listOf(SocialProvider.GOOGLE), providers.links(user).map { it.provider })
        assertEquals(1, log.list(user, null, 10).size)
        assertEquals(listOf(event.eventId), bus.published.map { it.eventId })
        assertEquals(listOf(event.eventId), outbox.unpublished(event.bucket).map { it.eventId }, "the outbox row waits for its publishedAt stamp")

        outbox.markPublished(event, Instant.now())
        assertTrue(outbox.unpublished(event.bucket).isEmpty())
    }

    @Test
    fun `a phone number can be claimed by one account only`() = runBlocking {
        val first = UserId.new()
        val second = UserId.new()
        val phone = PhoneNumber.parse("+254799${(100000..999999).random()}")!!
        store.commit(Changes(accounts = listOf(account(first, phone.e164))))

        assertTrue(accounts.claimPhone(phone, first))
        assertTrue(accounts.claimPhone(phone, first), "claiming your own number again is fine")
        assertFalse(accounts.claimPhone(phone, second))
        assertEquals(first, accounts.findByPhone(phone)?.userId)

        accounts.releasePhone(phone)
        assertNull(accounts.findByPhone(phone))
    }

    @Test
    fun `a Google login can belong to one account only`() = runBlocking {
        val first = UserId.new()
        val subject = "sub-${UUID.randomUUID()}"

        assertTrue(providers.claim(SocialProvider.GOOGLE, subject, first))
        assertFalse(providers.claim(SocialProvider.GOOGLE, subject, UserId.new()))
        assertEquals(first, providers.ownerOf(SocialProvider.GOOGLE, subject))
        assertNull(providers.ownerOf(SocialProvider.APPLE, subject))
    }

    @Test
    fun `the security log pages newest first`() = runBlocking {
        val user = UserId.new()
        repeat(5) { store.commit(Changes(securityEvents = listOf(SecurityEvent(user, null, SecurityEventType.SIGN_IN, "Pixel", "n$it")))) }

        val firstPage = log.list(user, null, 3)
        assertEquals(listOf("n4", "n3", "n2"), firstPage.map { it.detail })
        val secondPage = log.list(user, firstPage.last().eventId, 3)
        assertEquals(listOf("n1", "n0"), secondPage.map { it.detail })
    }

    @Test
    fun `a support case is found by its request id`() = runBlocking {
        val target = UserId.new()
        val request = Uuid7.generate()
        store.commit(
            Changes(
                recoveries = listOf(
                    SupportRecovery(target, request, RecoveryStatus.RECEIVED, "a@example.com", "lost it", null, null, null, null, null, SocialProvider.GOOGLE, "new-sub"),
                ),
            ),
        )

        val found = recoveries.find(request)
        assertNotNull(found)
        assertEquals(target, found.userId)
        assertEquals("new-sub", found.newSubject)
        assertNull(recoveries.find(Uuid7.generate()))
    }

    @Test
    fun `removing a provider link deletes its row`() = runBlocking {
        val user = UserId.new()
        val link = ProviderLink(user, SocialProvider.APPLE, "apple-sub", null, now)
        store.commit(Changes(providerLinks = listOf(link)))
        assertEquals(1, providers.links(user).size)

        store.commit(Changes(removedProviderLinks = listOf(link)))
        assertTrue(providers.links(user).isEmpty())
    }

    @Test
    fun `clearing the active device and the number really clears them`() = runBlocking {
        val user = UserId.new()
        val device = DeviceId.new()
        store.commit(Changes(accounts = listOf(account(user).copy(activeDeviceId = device))))
        assertEquals(device, accounts.find(user)?.activeDeviceId)

        store.commit(Changes(accounts = listOf(account(user, phone = null).copy(activeDeviceId = null))))
        assertNull(accounts.find(user)?.activeDeviceId)
        assertNull(accounts.find(user)?.phone)
    }
}
