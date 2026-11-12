package com.circaso.identity

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.domain.models.StartPhoneCommand
import com.circaso.identity.domain.models.VerifyCodeCommand
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** The store review login: one configured number signs in with a fixed code that is never texted; no other number can. */
class StoreReviewLoginTest {
    private val review = "+254700000001"
    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    @Test
    fun `the review number signs in with its fixed code and nothing is texted`() =
        runTest {
            val h = Harness(reviewPhone = review, reviewCode = "246810")
            val phone = Phone()
            val onboarding = h.principalOf(h.signIn("reviewer", phone).session.accessToken)
            val started = h.startPhone(StartPhoneCommand(onboarding, PhoneNumber.parse(review)!!)).data()
            assertTrue(h.sms.texts.isEmpty(), "no text for the review number")
            assertIs<Resource.Success<*>>(h.verifyCode(VerifyCodeCommand(onboarding, started.verificationId, "246810")))
            assertEquals(AccountStatus.ACTIVE, h.register(onboarding, phone).account.status)
        }

    @Test
    fun `the fixed code doesn't work for any other number`() =
        runTest {
            val h = Harness(reviewPhone = review, reviewCode = "246810")
            val onboarding = h.principalOf(h.signIn("someone", Phone()).session.accessToken)
            val started = h.startPhone(StartPhoneCommand(onboarding, PhoneNumber.parse("+254712345678")!!)).data()
            assertEquals(1, h.sms.texts.size, "a normal number still gets a text")
            assertIs<Resource.Error>(h.verifyCode(VerifyCodeCommand(onboarding, started.verificationId, "246810")))
        }

    @Test
    fun `no review code set means the review number is treated like any other`() =
        runTest {
            val h = Harness(reviewPhone = review, reviewCode = null)
            val onboarding = h.principalOf(h.signIn("reviewer", Phone()).session.accessToken)
            h.startPhone(StartPhoneCommand(onboarding, PhoneNumber.parse(review)!!)).data()
            assertEquals(1, h.sms.texts.size)
        }
}
