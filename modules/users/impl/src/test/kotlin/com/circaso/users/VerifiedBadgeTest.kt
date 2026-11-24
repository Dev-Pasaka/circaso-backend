package com.circaso.users

import com.circaso.identity.api.IdentityVerificationChanged
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.api.ProfileUpdated
import com.circaso.users.data.repositories.ContactRepositoryImpl
import com.circaso.users.data.repositories.DatabaseTransactor
import com.circaso.users.data.repositories.ProfileRepositoryImpl
import com.circaso.users.data.repositories.UsersStoreImpl
import com.circaso.users.di.usersDatabaseSpec
import com.circaso.users.domain.models.Contact
import com.circaso.users.domain.models.ContactSource
import com.circaso.users.domain.models.ContactStatus
import com.circaso.users.domain.ports.UsersFrame
import com.circaso.users.domain.support.ProfileProvisioner
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.support.UserNotifier
import com.circaso.users.domain.usecases.ApplyIdentityVerification
import com.circaso.users.domain.usecases.GetMyProfile
import com.circaso.users.domain.usecases.GetProfiles
import com.circaso.users.presentation.mappers.toDto
import com.circaso.users.presentation.mappers.toResponse
import kotlinx.coroutines.runBlocking
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The verified badge (2026-11-24) on PostgreSQL: identity's `IdentityVerificationChanged` shows on every profile
 * others see and on the person's own, moves the profile one version on, publishes `users.ProfileUpdated` and tells the
 * person and their contacts live. An older event arriving late changes nothing.
 */
class VerifiedBadgeTest {
    private val database = TestPostgres.database(usersDatabaseSpec)
    private val clock = Clock.systemUTC()
    private val bus = MemoryBus()
    private val transactor = DatabaseTransactor(database)
    private val profiles = ProfileRepositoryImpl(database, clock)
    private val contacts = ContactRepositoryImpl(database)
    private val store = UsersStoreImpl(database)
    private val frames = RecordingFrames()
    private val views = ProfileViews(FakeAvatars(), contacts)
    private val notifier = UserNotifier(frames, contacts)
    private val provisioner = ProfileProvisioner(profiles, transactor, clock)
    private val apply = ApplyIdentityVerification(provisioner, profiles, transactor, store, bus, notifier)
    private val getProfiles = GetProfiles(profiles, views)
    private val getMyProfile = GetMyProfile(provisioner, views)
    private val at: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeTest
    fun clean() = TestPostgres.truncate("users")

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    @Test
    fun `a verification shows on every profile, moves the version, and is told live`() =
        runBlocking {
            val amina = UserId.new()
            val bob = UserId.new()
            val before = provisioner.ensure(amina, "Amina")
            provisioner.ensure(bob, "Bob")
            contacts.connect(Contact(bob, amina, ContactSource.QR, ContactStatus.ACTIVE, null, at))
            assertFalse(getProfiles(setOf(amina)).data().getValue(amina).verified)

            apply(IdentityVerificationChanged(amina, true, at), at).data()

            val seen = getProfiles(setOf(amina)).data().getValue(amina)
            assertTrue(seen.verified)
            assertEquals(at, seen.verifiedAt)
            assertTrue(seen.toDto().verified)
            val mine = getMyProfile(amina).data()
            assertEquals(before.version + 1, mine.profile.version)
            assertTrue(mine.toResponse().verified)
            val event = bus.published.single { it.type == ProfileUpdated.TYPE }
            assertTrue("\"verified\"" in event.data.toString())
            val told =
                frames.sent
                    .filter { it.frame is UsersFrame.ProfileUpdated }
                    .map { it.to }
                    .toSet()
            assertEquals(setOf(amina, bob), told, "the person and whoever has them saved")
        }

    @Test
    fun `the badge goes away on a newer event, and a late older one is ignored`() =
        runBlocking {
            val amina = UserId.new()
            provisioner.ensure(amina, "Amina")
            apply(IdentityVerificationChanged(amina, true, at), at).data()

            apply(IdentityVerificationChanged(amina, false, null), at.plusSeconds(60)).data()
            val gone = getProfiles(setOf(amina)).data().getValue(amina)
            assertFalse(gone.verified)
            assertNull(gone.verifiedAt)

            val published = bus.published.size
            apply(IdentityVerificationChanged(amina, true, at), at.plusSeconds(30)).data()
            assertFalse(getProfiles(setOf(amina)).data().getValue(amina).verified)
            assertEquals(published, bus.published.size)
        }

    @Test
    fun `a profile edit never overwrites the badge`() =
        runBlocking {
            val amina = UserId.new()
            provisioner.ensure(amina, "Amina")
            apply(IdentityVerificationChanged(amina, true, at), at).data()
            val current = profiles.find(amina)!!
            profiles.update(current, current.copy(displayName = "Amina O.", verifiedAt = null))
            assertTrue(profiles.find(amina)!!.verified)
        }
}
