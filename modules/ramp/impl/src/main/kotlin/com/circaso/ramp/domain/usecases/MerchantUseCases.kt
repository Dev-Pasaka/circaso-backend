package com.circaso.ramp.domain.usecases

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.CollectionProviders
import com.circaso.integrations.api.IntegrationError
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
import com.circaso.ramp.domain.models.PaymentsIssue
import com.circaso.ramp.domain.models.ProfileCommand
import com.circaso.ramp.domain.models.UnlockedKeys
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
 * time, and required to match the one already set afterwards. The account is at the collections provider the network
 * uses now (a setting), so after staff switch provider the merchant connects again here. The answer is the merchant's own callback address to
 * paste into the provider. Allowed once the merchant has applied, before or after approval.
 */
class StorePaymentAccount(
    private val merchants: MerchantRepository,
    private val credentials: CredentialRepository,
    private val providers: CollectionProviders,
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
        // The account is at whichever provider the network uses now, and is sealed as that provider's.
        val current = settings.of(network)
        val collections =
            providers.named(current.collectionsProvider) ?: return Resource.error(RampError.CollectionsUnavailable)
        val checked = collections.verify(command.credentials)
        if (checked is Resource.Error) {
            val down = checked.error is IntegrationError.Unavailable
            val error = if (down) RampError.CollectionsUnavailable else RampError.CredentialsRejected
            return Resource.error(error, checked.cause)
        }
        val sealed =
            vault.seal(merchantId, current.collectionsProvider, command.credentials, command.pin)
                ?: return Resource.error(RampError.VaultUnavailable)
        credentials.save(merchantId, sealed, clock.instant())
        sessions.replace(merchantId, UnlockedKeys(current.collectionsProvider, command.credentials))
        val key = merchant.callbackKey ?: newKey(merchant)
        return Resource.success(CallbackView(Callbacks.url(current.callbackBaseUrl, key)))
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
 *
 * **The provider's service balance is checked before going online**: the prepaid wallet the provider takes its fee
 * from, per prompt. Below the network's floor (`provider_fee_floor_minor`, 0 for no check) the provider would refuse
 * the merchant's prompts, so RM6 refuses first, naming `provider_balance`. A balance the provider can't report never
 * blocks. Going online clears a payments issue left by an earlier refusal.
 */
class GoOnline(
    private val merchants: MerchantRepository,
    private val wallets: MerchantWalletRepository,
    private val credentials: CredentialRepository,
    private val views: MerchantViews,
    private val pins: PinGuard,
    private val sessions: TradingSessions,
    private val providers: CollectionProviders,
    private val settings: SettingsBook,
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
        // A payment account at another provider than the network's current one must be reconnected first.
        val missing = views.overview(merchant, network).missing
        if ("float" in missing || "payments" in missing) return Resource.error(RampError.MerchantSetupIncomplete)
        val keys =
            when (val opened = pins.open(merchantId, sealed, pin)) {
                is Resource.Error -> return opened
                is Resource.Success -> opened.data
            }
        if (serviceBalanceLow(merchant, sealed.provider, keys, network)) {
            return Resource.error(RampError.ProviderBalanceLow)
        }
        val now = clock.instant()
        val until = now.plus(Duration.ofMinutes(minutes.toLong()))
        sessions.unlock(merchantId, UnlockedKeys(sealed.provider, keys), until)
        if (merchant.paymentsIssue != null) merchants.setPaymentsIssue(merchantId, null, now)
        merchants.setOnlineUntil(merchantId, until)
        return Resource.success(OnlineView(online = true, until = until))
    }

    /**
     * Whether the provider reports a service balance under the floor. A merchant the provider already refused for
     * their balance needs something in it whatever the floor, or the first prompt would be refused again.
     */
    private suspend fun serviceBalanceLow(
        merchant: Merchant,
        provider: String,
        keys: CollectionCredentials,
        network: Network,
    ): Boolean {
        val refusedBefore = merchant.paymentsIssue == PaymentsIssue.SERVICE_BALANCE
        val floor = settings.of(network).providerFeeFloorMinor.coerceAtLeast(if (refusedBefore) 1 else 0)
        if (floor <= 0) return false
        val collections = providers.named(provider) ?: return false
        return when (val read = collections.balance(keys)) {
            is Resource.Success -> read.data.fees.amountMinor < floor
            is Resource.Error -> false
        }
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
