package com.circaso.identity

import com.circaso.identity.api.IdentityVerificationChanged
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.usecases.ApplyIdentityVerification
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.decode
import kotlinx.coroutines.test.runTest
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The verified badge (2026-11-24): ramp's `UserVerificationChanged` becomes the account's verification, which also
 * makes it eligible for support recovery, and `identity.IdentityVerificationChanged` when the answer changed.
 */
class IdentityVerificationTest {
    private val at = Instant.parse("2026-11-24T10:00:00Z")

    private fun Harness.apply() = ApplyIdentityVerification(store, store, store, bus)

    private suspend fun Harness.person(): UserId = signUp("amina", Phone(), "+254712345678").account.userId

    @Test
    fun `a verification marks the account verified, logs it and tells users once`() =
        runTest {
            val h = Harness()
            val userId = h.person()
            h.store.events.clear()

            assertIs<Resource.Success<Unit>>(h.apply()(userId, true, at, at))

            val account = h.store.accounts.getValue(userId)
            assertEquals(Verification.VERIFIED, account.verification)
            assertEquals(at, account.verifiedAt)
            assertEquals(
                SecurityEventType.IDENTITY_VERIFIED,
                h.store.log
                    .last()
                    .type,
            )
            assertEquals(
                IdentityVerificationChanged(userId, true, at),
                h.store.events
                    .single()
                    .decode<IdentityVerificationChanged>(),
            )

            // The same report again (a redelivery, the periodic re-announcement) changes nothing.
            h.apply()(userId, true, at, at)
            assertEquals(1, h.store.events.size)
        }

    @Test
    fun `a newer report un-verifies, and an older one arriving late is ignored`() =
        runTest {
            val h = Harness()
            val userId = h.person()
            h.apply()(userId, true, at, at)
            h.store.events.clear()

            h.apply()(userId, false, null, at.plusSeconds(60))
            val account = h.store.accounts.getValue(userId)
            assertEquals(Verification.NONE, account.verification)
            assertNull(account.verifiedAt)
            assertEquals(
                SecurityEventType.IDENTITY_UNVERIFIED,
                h.store.log
                    .last()
                    .type,
            )
            assertEquals(
                false,
                h.store.events
                    .single()
                    .decode<IdentityVerificationChanged>()
                    .verified,
            )

            h.apply()(userId, true, at, at.plusSeconds(30))
            assertEquals(
                Verification.NONE,
                h.store.accounts
                    .getValue(userId)
                    .verification,
                "older than the last",
            )
            assertEquals(1, h.store.events.size)
        }

    @Test
    fun `an account identity doesn't know is ignored`() =
        runTest {
            val h = Harness()
            assertIs<Resource.Success<Unit>>(h.apply()(UserId.new(), true, at, at))
            assertTrue(h.store.events.isEmpty())
        }
}
