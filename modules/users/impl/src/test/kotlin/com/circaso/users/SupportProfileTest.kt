package com.circaso.users

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.SupportAccountCreated
import com.circaso.shared.CircasoJson
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.users.domain.models.ConnectVia
import com.circaso.users.domain.models.HandleAvailability
import com.circaso.users.domain.support.ProfileViews
import com.circaso.users.domain.usecases.GetProfiles
import com.circaso.users.presentation.dto.PublicProfileDto
import com.circaso.users.presentation.mappers.toDto
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The support account's profile (decision 2026-10-31): made from identity's event, shown as verified support. */
class SupportProfileTest {
    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun Resource<*>.errorCode(): String = (this as Resource.Error).error.code

    private suspend fun Harness.support(handle: String = "circaso"): UserId {
        val id = UserId.new()
        directory.statuses[id] = AccountStatus.ACTIVE
        onSupportCreated(SupportAccountCreated(id, "Circaso Support", handle)).data()
        return id
    }

    @Test
    fun `a reserved handle is free for a support account and still refused to a person`() =
        runTest {
            val h = Harness()
            val amina = h.person("Amina")

            assertEquals("circaso", h.checkSupportHandle("@Circaso").data())
            assertEquals("users.handle_invalid", h.claimHandle(amina, "circaso").errorCode())
            assertEquals(HandleAvailability.INVALID, h.checkHandle(amina, "circaso").data().availability)
            assertEquals("users.handle_invalid", h.checkSupportHandle("9lives").errorCode())

            h.claimHandle(amina, "amina").data()
            assertEquals("users.handle_taken", h.checkSupportHandle("amina").errorCode())
        }

    @Test
    fun `the event makes a support profile with its handle, once`() =
        runTest {
            val h = Harness()
            val support = h.support()

            val profile = h.users.profiles.getValue(support)
            assertTrue(profile.support)
            assertEquals("Circaso Support", profile.displayName)
            assertEquals("circaso", profile.handle)
            assertEquals(
                support,
                h.users.handles
                    .getValue("circaso")
                    .userId,
            )

            h.onSupportCreated(SupportAccountCreated(support, "Another name", "help")).data()
            assertEquals(profile, h.users.profiles.getValue(support), "a second delivery changes nothing")
        }

    @Test
    fun `a handle taken since ops checked it is left off, and the profile is still support`() =
        runTest {
            val h = Harness()
            val first = h.support("circaso")
            val second = h.support("circaso")

            assertEquals(
                first,
                h.users.handles
                    .getValue("circaso")
                    .userId,
            )
            assertNull(
                h.users.profiles
                    .getValue(second)
                    .handle,
            )
            assertTrue(
                h.users.profiles
                    .getValue(second)
                    .support,
            )
        }

    @Test
    fun `people find the support account by its reserved handle and see support true, a person sees false`() =
        runTest {
            val h = Harness()
            val support = h.support()
            val amina = h.person("Amina")
            h.claimHandle(amina, "amina").data()
            val brian = h.person("Brian")

            val found = h.lookupHandle(brian, "@circaso").data()
            assertEquals(support, found.userId)
            assertTrue(found.support)
            assertFalse(h.lookupHandle(brian, "amina").data().support)

            val connected = h.connect(brian, ConnectVia.HANDLE, "circaso").data()
            assertTrue(connected.user.support)
        }

    @Test
    fun `other modules and the app's JSON carry the support flag, false for everyone else`() =
        runTest {
            val h = Harness()
            val support = h.support()
            val amina = h.person("Amina")

            val profiles = GetProfiles(h.users, ProfileViews(FakeAvatars(), h.users))(setOf(support, amina)).data()
            assertTrue(profiles.getValue(support).support)
            assertFalse(profiles.getValue(amina).support)

            val json = CircasoJson.encodeToString(PublicProfileDto.serializer(), profiles.getValue(support).toDto())
            assertTrue(json.contains("\"support\":true"), json)
            val old =
                CircasoJson.decodeFromString(
                    PublicProfileDto.serializer(),
                    """{"userId":"x","displayName":"A"}""",
                )
            assertFalse(old.support, "an answer without the field reads as a person")
        }
}
