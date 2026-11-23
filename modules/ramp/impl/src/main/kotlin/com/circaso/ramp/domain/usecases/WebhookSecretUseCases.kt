package com.circaso.ramp.domain.usecases

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.MerchantOverview
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.ports.WebhookSecretVault
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.support.MerchantViews
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Clock

/**
 * RM5c: the merchant's FinoQ webhook signing secret, copied from their FinoQ dashboard (2026-11-23). Setting it
 * replaces any earlier one; [secret] null removes it. It is sealed under the master key alone ([WebhookSecretVault]:
 * callbacks come while the merchant is offline), never returned, and RM2 shows only that it is set and when. Setting
 * one needs the merchant's callback address (RM5), the address FinoQ signs its callbacks to; removing is always allowed.
 */
class SetWebhookSecret(
    private val merchants: MerchantRepository,
    private val vault: WebhookSecretVault,
    private val views: MerchantViews,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        secret: String?,
    ): Resource<MerchantOverview> {
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        if (secret == null) {
            merchants.setWebhookSecret(merchantId, null, clock.instant())
        } else {
            if (merchant.state == MerchantState.NEW || merchant.callbackKey == null) {
                return Resource.error(RampError.MerchantSetupIncomplete)
            }
            val sealed = vault.seal(merchantId, secret) ?: return Resource.error(RampError.VaultUnavailable)
            merchants.setWebhookSecret(merchantId, sealed, clock.instant())
        }
        val saved = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        return Resource.success(views.overview(saved, network))
    }
}

/**
 * The secret a callback on the address ending in [callbackKey] is checked against, for integrations' webhook. Null
 * when the merchant set none, the key isn't theirs, or it doesn't open (no master key, or another one): then the
 * callback is handled as before, which is safe because it is only a doorbell.
 */
class CallbackSigningSecret(private val merchants: MerchantRepository, private val vault: WebhookSecretVault) {
    suspend operator fun invoke(callbackKey: String): String? {
        val stored = merchants.webhookSecret(callbackKey) ?: return null
        return vault.open(stored.merchantId, stored.sealed)
    }
}
