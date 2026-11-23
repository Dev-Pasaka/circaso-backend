package com.circaso.ramp

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.data.support.EnvelopeVault
import com.circaso.ramp.data.support.MasterKeyWebhookSecrets
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.MerchantOverview
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.presentation.mappers.toResponse
import com.circaso.shared.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RM5c: the merchant's FinoQ webhook signing secret (2026-11-23). Sealed under the master key alone so a callback can
 * be checked while the merchant is offline, never returned, and only "set, and when" shown on RM2.
 */
class WebhookSecretTest {
    private val ramp = Harness()
    private val secret = "whsec_Zr8Lq2Vx7Tn4Kp9W"
    private val crynce = ramp.crynce

    private fun <T> Resource<T>.error() = (this as Resource.Error).error

    private fun <T> Resource<T>.data() = (this as Resource.Success).data

    private suspend fun set(value: String?, merchantId: MerchantId = crynce.merchantId): Resource<MerchantOverview> =
        ramp.setWebhookSecret(merchantId, TESTNET, value)

    @Test
    fun `a secret set is sealed, opened only for the merchant's own callback address, and shown only as set`() =
        runTest {
            val overview = set(secret).data()

            val sealed = ramp.merchants.webhookSecrets.getValue(crynce.merchantId)
            assertFalse(sealed.decodeToString().contains(secret), "never stored as plaintext")
            assertEquals(secret, ramp.callbackSigningSecret(crynce.callbackKey!!))
            assertNull(ramp.callbackSigningSecret("someone-0123456789abcdef"))

            val response = overview.toResponse()
            assertTrue(response.webhookSecretSet)
            assertEquals(ramp.clock.instant().toString(), response.webhookSecretUpdatedAt)
            assertFalse(response.toString().contains(secret), "RM2 never carries the secret")
        }

    @Test
    fun `setting it again replaces it and moves the date, and removing it goes back to no check`() =
        runTest {
            set(secret)
            ramp.clock.advance(300)

            val replaced = set("whsec_second-one").data()
            assertEquals("whsec_second-one", ramp.callbackSigningSecret(crynce.callbackKey!!))
            assertEquals(ramp.clock.instant().toString(), replaced.toResponse().webhookSecretUpdatedAt)

            val removed = set(null).data().toResponse()
            assertFalse(removed.webhookSecretSet)
            assertNull(removed.webhookSecretUpdatedAt)
            assertNull(ramp.callbackSigningSecret(crynce.callbackKey!!))
        }

    @Test
    fun `it needs a callback address first, and a server without a master key can't keep one`() =
        runTest {
            ramp.merchants.rows[crynce.merchantId] = crynce.copy(callbackKey = null)
            assertEquals(RampError.MerchantSetupIncomplete, set(secret).error())

            ramp.merchants.rows[crynce.merchantId] = crynce.copy(state = MerchantState.NEW)
            assertEquals(RampError.MerchantSetupIncomplete, set(secret).error())

            ramp.merchants.rows[crynce.merchantId] = crynce
            ramp.masterKey = null
            assertEquals(RampError.VaultUnavailable, set(secret).error())
            assertNull(crynce.webhookSecretUpdatedAt)
        }

    @Test
    fun `an unknown merchant is not found`() =
        runTest {
            assertEquals(RampError.MerchantNotFound, set(secret, MerchantId.new()).error())
        }

    @Test
    fun `a sealed secret doesn't open for another merchant or under another master key`() {
        val vault = MasterKeyWebhookSecrets({ EnvelopeVault.masterKey(MASTER_KEY) })
        val sealed = assertNotNull(vault.seal(crynce.merchantId, secret))

        assertEquals(secret, vault.open(crynce.merchantId, sealed))
        assertNull(vault.open(MerchantId.new(), sealed))
        val other = MasterKeyWebhookSecrets({ EnvelopeVault.masterKey("ff".repeat(32)) })
        assertNull(other.open(crynce.merchantId, sealed))
        assertEquals("MasterKeyWebhookSecrets(***)", vault.toString())
    }
}
