package com.circaso.ramp.domain.usecases

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MobileCollections
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.CallbackView
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantOverview
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.OnlineView
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.PaymentAccountCommand
import com.circaso.ramp.domain.models.ProfileCommand
import com.circaso.ramp.domain.ports.CredentialVault
import com.circaso.ramp.domain.ports.RampTokens
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.repositories.CredentialRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.OrderRepository
import com.circaso.ramp.domain.support.Callbacks
import com.circaso.ramp.domain.support.MerchantViews
import com.circaso.ramp.domain.support.PinGuard
import com.circaso.ramp.domain.support.Presence
import com.circaso.ramp.domain.support.SettingsBook
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Duration

/** RM2: the merchant, what is set up, the float, and what is still missing before they can go online. */
class GetMerchant(private val merchants: MerchantRepository, private val views: MerchantViews) {
    suspend operator fun invoke(merchantId: MerchantId, network: Network): Resource<MerchantOverview> {
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        return Resource.success(views.overview(merchant, network))
    }
}

/**
 * RM1: the merchant's name and contact phone. The first time (from `NEW`) it also applies as a partner (`APPLIED`);
 * later it only updates the two fields and leaves the state alone.
 */
class ApplyAsMerchant(private val merchants: MerchantRepository, private val views: MerchantViews) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        profile: ProfileCommand,
    ): Resource<MerchantOverview> {
        merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        merchants.setProfile(merchantId, profile.displayName, profile.contactPhone.e164)
        val saved = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        return Resource.success(views.overview(saved, network))
    }
}

/**
 * RM5: stores the merchant's payment account (decision 2026-11-22: the credentials are the merchant's, and are only
 * ever entered here). They are checked with the provider first, then sealed under the PIN: set by this call the first
 * time, and required to match the one already set afterwards. The answer is the merchant's own callback address to
 * paste into the provider. Allowed once the merchant has applied, before or after approval.
 */
class StorePaymentAccount(
    private val merchants: MerchantRepository,
    private val credentials: CredentialRepository,
    private val collections: MobileCollections,
    private val vault: CredentialVault,
    private val pins: PinGuard,
    private val sessions: TradingSessions,
    private val tokens: RampTokens,
    private val settings: SettingsBook,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        command: PaymentAccountCommand,
    ): Resource<CallbackView> {
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        when (merchant.state) {
            MerchantState.NEW -> return Resource.error(RampError.MerchantSetupIncomplete)
            MerchantState.SUSPENDED -> return Resource.error(RampError.MerchantNotApproved)
            MerchantState.APPLIED, MerchantState.APPROVED -> Unit
        }
        // A PIN already set must be the one given: storing a new account never resets it.
        credentials.find(merchantId)?.let { existing ->
            val opened = pins.open(merchantId, existing, command.pin)
            if (opened is Resource.Error) return opened
        }
        val checked = collections.verify(command.credentials)
        if (checked is Resource.Error) {
            val down = checked.error is IntegrationError.Unavailable
            val error = if (down) RampError.CollectionsUnavailable else RampError.CredentialsRejected
            return Resource.error(error, checked.cause)
        }
        val sealed =
            vault.seal(merchantId, command.credentials, command.pin)
                ?: return Resource.error(RampError.VaultUnavailable)
        credentials.save(merchantId, sealed, clock.instant())
        sessions.replace(merchantId, command.credentials)
        val key = merchant.callbackKey ?: newKey(merchant)
        return Resource.success(CallbackView(Callbacks.url(settings.of(network).callbackBaseUrl, key)))
    }

    private suspend fun newKey(merchant: Merchant): String =
        Callbacks.key(merchant.displayName, tokens.callbackToken()).also {
            merchants.setCallbackKey(merchant.merchantId, it)
        }
}

/** RM14: a new callback address for the merchant. The old one stops working at once. */
class RotateCallback(
    private val merchants: MerchantRepository,
    private val credentials: CredentialRepository,
    private val tokens: RampTokens,
    private val settings: SettingsBook,
) {
    suspend operator fun invoke(merchantId: MerchantId, network: Network): Resource<CallbackView> {
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        credentials.find(merchantId) ?: return Resource.error(RampError.MerchantSetupIncomplete)
        val key = Callbacks.key(merchant.displayName, tokens.callbackToken())
        merchants.setCallbackKey(merchantId, key)
        return Resource.success(CallbackView(Callbacks.url(settings.of(network).callbackBaseUrl, key)))
    }
}

/**
 * RM6: goes online for a trading window. The PIN unwraps the payment keys **into this node's memory only**; the
 * merchant must be approved, with a float wallet on the network and a payment account. A float the custodian reports
 * as too small for the smallest order refuses too; one it can't report doesn't.
 */
class GoOnline(
    private val merchants: MerchantRepository,
    private val wallets: MerchantWalletRepository,
    private val credentials: CredentialRepository,
    private val views: MerchantViews,
    private val pins: PinGuard,
    private val sessions: TradingSessions,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        pin: String,
        minutes: Int,
    ): Resource<OnlineView> {
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        if (merchant.state != MerchantState.APPROVED) return Resource.error(RampError.MerchantNotApproved)
        val sealed = credentials.find(merchantId) ?: return Resource.error(RampError.MerchantSetupIncomplete)
        wallets.find(merchantId, network) ?: return Resource.error(RampError.MerchantSetupIncomplete)
        if ("float" in views.overview(merchant, network).missing) {
            return Resource.error(RampError.MerchantSetupIncomplete)
        }
        val keys =
            when (val opened = pins.open(merchantId, sealed, pin)) {
                is Resource.Error -> return opened
                is Resource.Success -> opened.data
            }
        val until = clock.instant().plus(Duration.ofMinutes(minutes.toLong()))
        sessions.unlock(merchantId, keys, until)
        merchants.setOnlineUntil(merchantId, until)
        return Resource.success(OnlineView(online = true, until = until))
    }
}

/** RM6: goes offline. New orders stop; open ones finish. */
class GoOffline(private val presence: Presence) {
    suspend operator fun invoke(merchantId: MerchantId): Resource<OnlineView> {
        presence.goOffline(merchantId)
        return Resource.success(OnlineView(online = false, until = null))
    }
}

/** RM9: re-wraps the data key under a new PIN. The old PIN is required, and wrong tries count as everywhere. */
class ChangePin(private val credentials: CredentialRepository, private val pins: PinGuard, private val clock: Clock) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        currentPin: String,
        newPin: String,
    ): Resource<Unit> {
        val sealed = credentials.find(merchantId) ?: return Resource.error(RampError.MerchantSetupIncomplete)
        return when (val rewrapped = pins.rewrap(merchantId, sealed, currentPin, newPin)) {
            is Resource.Error -> {
                rewrapped
            }

            is Resource.Success -> {
                credentials.save(merchantId, rewrapped.data, clock.instant())
                Resource.success(Unit)
            }
        }
    }
}

/** RM7: the merchant's orders on a network, newest first, a page at a time by keyset. */
class ListMerchantOrders(private val orders: OrderRepository) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        limit: Int,
        after: PageCursor?,
    ): Resource<List<Order>> =
        Resource.success(orders.ofMerchant(merchantId, network, limit.coerceIn(1, MAX_LIMIT), after))

    private companion object {
        const val MAX_LIMIT = 200
    }
}

/**
 * `ramp.end-sessions`: clears the trading window of every merchant whose window has ended, and drops from this node's
 * memory the keys of merchants who are no longer online once none of their orders still waits for its payment.
 */
class EndSessions(
    private val merchants: MerchantRepository,
    private val orders: OrderRepository,
    private val sessions: TradingSessions,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int {
        val now = clock.instant()
        var handled = merchants.endPassedWindows(now)
        sessions.held().forEach { merchantId ->
            val online = merchants.find(merchantId)?.isTradingUntil(now) == true
            if (!online && !orders.hasAwaitingPayment(merchantId)) {
                sessions.end(merchantId)
                handled++
            }
        }
        return handled
    }
}
