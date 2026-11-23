package com.circaso.ramp

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.KycDeclineReason
import com.circaso.integrations.api.KycDocument
import com.circaso.integrations.api.KycEnvironment
import com.circaso.integrations.api.KycStatus
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.KycState
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.PaymentAccountCommand
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.models.Subject
import com.circaso.ramp.domain.models.VerificationStatus
import com.circaso.shared.Network
import com.circaso.shared.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A merchant's identity verification (decision 2026-11-22): the hosted check with the portal's callback, RM2's `kyc`
 * step, and the three gates that rest on it: staff approval, going online, and the engine's choice of a merchant.
 */
class MerchantKycTest {
    private val ramp = Harness()
    private val crynce get() = ramp.crynce
    private val subject get() = Subject.merchant(crynce.merchantId)

    private fun <T> Resource<T>.error() = (this as Resource.Error).error

    private fun <T> Resource<T>.value() = (this as Resource.Success).data

    private suspend fun overview(merchant: Merchant = crynce) = ramp.getMerchant(merchant.merchantId, TESTNET).value()

    private fun session() = ramp.sessionOf(subject)

    /** A merchant who signed in and gave their profile: `APPLIED`, waiting for staff. */
    private suspend fun applied(): Merchant {
        val signedIn = ramp.verifyCodeFor("pay@juma.co.ke")
        val merchantId = signedIn.merchant.merchant.merchantId
        ramp.merchants.setProfile(merchantId, "Juma", "+254711000111")
        return ramp.merchants.rows.getValue(merchantId)
    }

    @Test
    fun `the merchant check runs the merchant workflow on the provider's hosted page, back to the portal`() =
        runTest {
            ramp.requireKyc()

            val started = ramp.startMerchantKyc(crynce.merchantId, TESTNET).value()

            assertEquals(KycState.PENDING, started.state)
            assertEquals("https://verify.test/session/token-1", started.link?.url, "where the portal sends the browser")
            val asked = ramp.didit.started.single()
            assertEquals("merchant:${crynce.merchantId}", asked.subject)
            assertEquals(RampSettings.SANDBOX_WORKFLOW_MERCHANT, asked.workflowId, "ID, selfie and AML")
            assertEquals("https://merchants.circaso.com/kyc/done", asked.callbackUrl)
            assertEquals(KycEnvironment.SANDBOX, asked.environment)

            val again = ramp.startMerchantKyc(crynce.merchantId, TESTNET).value()
            assertEquals(started.link?.url, again.link?.url, "the same open check")
            assertEquals(1, ramp.didit.started.size)
        }

    @Test
    fun `a merchant needs a profile first, and a suspended one can't start`() =
        runTest {
            ramp.requireKyc()
            val fresh = ramp.verifyCodeFor("new@shop.co.ke").merchant.merchant
            assertEquals(MerchantState.NEW, fresh.state)
            assertEquals(RampError.MerchantSetupIncomplete, ramp.startMerchantKyc(fresh.merchantId, TESTNET).error())

            ramp.decideMerchant(crynce.merchantId, approve = false, by = "staff")
            assertEquals(RampError.MerchantNotApproved, ramp.startMerchantKyc(crynce.merchantId, TESTNET).error())
        }

    @Test
    fun `RM2 shows the kyc step - missing until verified, and it is what the portal reads after the callback`() =
        runTest {
            ramp.requireKyc()

            val before = overview()
            assertEquals(KycState.NONE, before.kyc.state)
            assertTrue(before.kyc.required && before.kyc.canStart)
            assertTrue("kyc" in before.missing)
            assertFalse(before.canGoOnline)

            ramp.startMerchantKyc(crynce.merchantId, TESTNET)
            assertEquals(KycState.PENDING, overview().kyc.state)
            ramp.didit.approve(session())

            // No webhook: the hosted page sent the merchant back, and the portal reads RM2.
            ramp.clock.advance(6)
            val after = overview()
            assertEquals(KycState.VERIFIED, after.kyc.state)
            assertFalse("kyc" in after.missing)
            assertTrue(ramp.frames.kyc.isEmpty(), "merchants get no frames: the portal polls")
        }

    @Test
    fun `a merchant who finished the hosted page reads submitted on RM2, and RM3 hands out no link`() =
        runTest {
            ramp.requireKyc()
            ramp.startMerchantKyc(crynce.merchantId, TESTNET)
            ramp.didit.submit(session())

            // Back from the hosted page before the provider decided: the portal reads RM2.
            ramp.clock.advance(6)
            val waiting = overview()
            assertEquals(KycState.PENDING, waiting.kyc.state)
            assertTrue(waiting.kyc.submitted && !waiting.kyc.canStart)
            assertTrue("kyc" in waiting.missing, "not verified yet")
            val again = (ramp.startMerchantKyc(crynce.merchantId, TESTNET) as Resource.Success).data
            assertTrue(again.submitted)
            assertNull(again.link, "the hosted page is not reopened")
            assertEquals(1, ramp.didit.started.size)

            ramp.didit.approve(session())
            ramp.clock.advance(6)
            val after = overview()
            assertEquals(KycState.VERIFIED to false, after.kyc.state to after.kyc.submitted)
        }

    @Test
    fun `a declined merchant sees why and may try again`() =
        runTest {
            ramp.requireKyc()
            ramp.startMerchantKyc(crynce.merchantId, TESTNET)
            ramp.didit.decline(session(), KycDeclineReason.SCREENING)
            ramp.webhook(session())

            val declined = overview().kyc
            assertEquals(KycState.DECLINED, declined.state)
            assertEquals("not_approved", declined.reason?.code, "a screening hit is never named")
            assertFalse(declined.canStart)
            assertTrue("kyc" in overview().missing)
        }

    @Test
    fun `where the network doesn't require KYC the step isn't missing`() =
        runTest {
            val overview = overview()

            assertFalse(overview.kyc.required)
            assertFalse("kyc" in overview.missing)
        }

    // Staff approval

    @Test
    fun `staff can't approve a merchant whose identity isn't verified where an open network requires it`() =
        runTest {
            ramp.requireKyc()
            val juma = applied()

            val refused =
                assertIs<RampError.MerchantKycRequired>(ramp.decideMerchant(juma.merchantId, true, "staff").error())
            assertEquals("ramp.merchant_kyc_required", refused.code)
            assertEquals(
                "kyc",
                refused.details
                    ?.fields
                    ?.single()
                    ?.field,
            )
            assertTrue("TESTNET" in refused.message)
            assertEquals(
                MerchantState.APPLIED,
                ramp.merchants.rows
                    .getValue(juma.merchantId)
                    .state,
            )

            ramp.startMerchantKyc(juma.merchantId, TESTNET)
            val session = ramp.sessionOf(Subject.merchant(juma.merchantId))
            ramp.didit.set(session, KycStatus.IN_REVIEW)
            ramp.webhook(session)
            assertIs<RampError.MerchantKycRequired>(ramp.decideMerchant(juma.merchantId, true, "staff").error())

            ramp.didit.approve(session, KycDocument("KEN", "Passport", "J0001"))
            ramp.webhook(session)
            val approved = ramp.decideMerchant(juma.merchantId, true, "staff").value()
            assertEquals(MerchantState.APPROVED, approved.merchant.state)
            assertEquals(VerificationStatus.VERIFIED, approved.verifications.single().status)
        }

    @Test
    fun `approval asks nothing of a network that is OFF, or one that doesn't require KYC`() =
        runTest {
            // Mainnet always requires KYC but is OFF; the testnet is open and doesn't require it.
            val juma = applied()
            assertEquals(RampState.OFF, ramp.book.of(Network.MAINNET).state)

            assertEquals(
                MerchantState.APPROVED,
                ramp
                    .decideMerchant(juma.merchantId, true, "staff")
                    .value()
                    .merchant.state,
            )
        }

    @Test
    fun `an open mainnet needs the merchant verified live - a sandbox approval doesn't count`() =
        runTest {
            ramp.requireKyc()
            ramp.settingsRows.set(
                Network.MAINNET,
            ) { it.copy(state = RampState.PREVIEW, kycWorkflowMerchant = "wf-live-merchant") }
            ramp.book.invalidate()
            val juma = applied()
            ramp.verified(juma)

            val refused =
                assertIs<RampError.MerchantKycRequired>(ramp.decideMerchant(juma.merchantId, true, "staff").error())
            assertTrue("MAINNET" in refused.message && "TESTNET" !in refused.message)
        }

    @Test
    fun `a suspension needs no verification`() =
        runTest {
            ramp.requireKyc()

            assertEquals(
                MerchantState.SUSPENDED,
                ramp
                    .decideMerchant(crynce.merchantId, approve = false, by = "staff")
                    .value()
                    .merchant.state,
            )
        }

    @Test
    fun `staff see each merchant's verification`() =
        runTest {
            ramp.requireKyc()
            ramp.verified(crynce)

            val listed = ramp.listMerchants(null, 50).value().single { it.merchant.merchantId == crynce.merchantId }

            val verification = listed.verifications.single()
            assertEquals(
                KycEnvironment.SANDBOX to VerificationStatus.VERIFIED,
                verification.environment to verification.status,
            )
        }

    // Going online, and the engine

    @Test
    fun `an unverified merchant can't go online where KYC is required, and the field says why`() =
        runTest {
            ramp.requireKyc()
            val account = PaymentAccountCommand(CollectionCredentials("api-user", "api-pass", "911"), "482913")
            assertIs<Resource.Success<*>>(ramp.storePayments(crynce.merchantId, TESTNET, account))

            val refused = ramp.goOnline(crynce.merchantId, TESTNET, "482913", 60).error()

            assertEquals(RampError.MerchantKycMissing, refused)
            assertEquals("ramp.merchant_setup_incomplete", refused.code)
            assertEquals(
                "kyc",
                refused.details
                    ?.fields
                    ?.single()
                    ?.field,
            )

            ramp.verified(crynce)
            assertIs<Resource.Success<*>>(ramp.goOnline(crynce.merchantId, TESTNET, "482913", 60))
        }

    @Test
    fun `the engine gives no order to a merchant who isn't verified on the order's network, online or not`() =
        runTest {
            ramp.requireKyc()
            ramp.verified(ramp.amina)
            assertNotNull(crynce.onlineUntil, "online, with float and keys")

            assertFalse(ramp.status(ramp.amina, TESTNET).value().merchantOnline)
            assertEquals(RampError.NoMerchantAvailable, ramp.buy().error())
            assertTrue(ramp.collections.prompts.isEmpty())

            ramp.verified(crynce)
            assertTrue(ramp.status(ramp.amina, TESTNET).value().merchantOnline)
            assertIs<Resource.Success<*>>(ramp.buy())
        }

    @Test
    fun `resetting a merchant's verification takes them out of the engine until they verify again`() =
        runTest {
            ramp.requireKyc()
            ramp.verified(ramp.amina)
            ramp.verified(crynce)

            assertTrue(ramp.resetVerification(subject, KycEnvironment.SANDBOX).value())

            assertNull(ramp.verificationRows.rows[subject to KycEnvironment.SANDBOX])
            assertEquals(RampError.NoMerchantAvailable, ramp.buy().error())
            assertTrue("kyc" in overview().missing)
        }
}
