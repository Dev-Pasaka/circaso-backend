package com.circaso.ramp.domain.support

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.integrations.api.CollectionProviders
import com.circaso.integrations.api.MobileCollections
import com.circaso.integrations.api.TopUpLookup
import com.circaso.integrations.api.TopUpRejection
import com.circaso.integrations.api.TopUpRequest
import com.circaso.integrations.api.TopUpResult
import com.circaso.integrations.api.TopUpStatus
import com.circaso.integrations.api.TopUpWallet
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.TopUp
import com.circaso.ramp.domain.models.TopUpCommand
import com.circaso.ramp.domain.models.TopUpConfirmation
import com.circaso.ramp.domain.models.TopUpFailure
import com.circaso.ramp.domain.models.TopUpState
import com.circaso.ramp.domain.models.TopUpTarget
import com.circaso.ramp.domain.models.UnlockedKeys
import com.circaso.ramp.domain.ports.PayoutJournal
import com.circaso.ramp.domain.ports.TradingSessions
import com.circaso.ramp.domain.repositories.CredentialRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.TopUpRepository
import com.circaso.shared.FiatAmount
import com.circaso.shared.Network
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * A merchant tops up one of their own three wallets at their provider (2026-11-23; three since 2026-11-24: PayHero's
 * service wallet for buying's fees, FinoQ's service wallet for selling's fees, FinoQ's payment wallet for payouts): the
 * owner's "they just enter an amount, get an STK push, and top up immediately". The prompt goes **only to the
 * merchant's own contact phone**, never a number from the request, so nobody can use the portal to push prompts at
 * other people.
 *
 * **The keys.** The prompt is asked for with the merchant's own provider account, so it needs their unlocked keys. A
 * merchant online on this node has them; an offline one gives their PIN with the request, which unlocks the account on
 * this node **without** putting them online ([TradingSessions.hold]): an empty service wallet is exactly what keeps a
 * merchant from going online, so asking them to go online first would lock them out. The held keys go when nothing open
 * needs them (`ramp.end-sessions`).
 *
 * **Sent once.** The row is written `REQUESTED` before the prompt is asked for, one open per wallet; the client never
 * retries, and nothing here sends a second prompt for the same top-up.
 *
 * **Confirmed only by the provider or the balance.** A callback is a doorbell ([rung]). A top-up is `CONFIRMED` when
 * the provider's status says it was paid, or when that wallet's balance has risen by at least [BALANCE_SHARE_PERCENT]
 * of the amount since it was read before the prompt (the fee FinoQ may take from a wallet collection is unknown).
 * `FAILED` only when the provider says so (or refused the prompt outright, when nothing was sent). A top-up nothing
 * could settle within [WINDOW] closes as `UNKNOWN`, which a late "paid" from the provider still confirms.
 */
class MerchantTopUps(
    private val topUps: TopUpRepository,
    private val merchants: MerchantRepository,
    private val credentials: CredentialRepository,
    private val pins: PinGuard,
    private val sessions: TradingSessions,
    private val providers: CollectionProviders,
    private val balances: ProviderBalancesReader,
    private val settings: SettingsBook,
    private val clock: Clock,
    private val journal: PayoutJournal,
    private val alerts: MerchantAlerts,
) {
    suspend fun start(
        merchantId: MerchantId,
        network: Network,
        command: TopUpCommand,
    ): Resource<TopUp> {
        val amountMinor = command.amountMinor
        if (amountMinor !in MIN_MINOR..MAX_MINOR || amountMinor % FiatAmount.MINOR_PER_UNIT != 0L) {
            return Resource.error(RampError.TopUpAmountInvalid(MIN_SHILLINGS, MAX_SHILLINGS))
        }
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        if (merchant.state != MerchantState.APPROVED) return Resource.error(RampError.MerchantNotApproved)
        val phone = PhoneNumber.parse(merchant.contactPhone) ?: return Resource.error(RampError.MerchantSetupIncomplete)
        if (command.target.needsAccountId && merchant.payoutAccountId == null) {
            return Resource.error(RampError.TopUpAccountMissing)
        }
        if (topUps.open(merchantId, command.target) != null) return Resource.error(RampError.TopUpInProgress)
        val keys =
            when (val unlocked = keysFor(merchantId, command.pin)) {
                is Resource.Error -> return unlocked
                is Resource.Success -> unlocked.data
            }
        val collections = providers.named(keys.provider) ?: return Resource.error(RampError.CollectionsUnavailable)

        val before = balances.read(merchant)
        val now = clock.instant()
        val topUp =
            TopUp(
                topUpId = Uuid7.generate(now),
                merchantId = merchantId,
                target = command.target,
                provider = keys.provider,
                amountMinor = amountMinor,
                payer = phone,
                state = TopUpState.REQUESTED,
                failure = null,
                outcome = SENDING,
                providerReference = null,
                checkoutId = null,
                receipt = null,
                confirmedBy = null,
                balanceBeforeMinor = before?.of(command.target)?.minor,
                balanceAfterMinor = null,
                createdAt = now,
                updatedAt = now,
                closedAt = null,
            )
        // Written before the prompt is asked for, under the one-open-per-wallet key.
        if (!topUps.insert(topUp)) return Resource.error(RampError.TopUpInProgress)
        val result = send(collections, keys.credentials, request(topUp, merchant, network))
        journal.step("topup sent", "${topUp.topUpId} merchant=$merchantId wallet=${topUp.target}", said(result))
        return Resource.success(sent(topUp, result))
    }

    /** One of the merchant's top-ups; one still open is asked about first (at most every [ASK_EVERY]). */
    suspend fun view(merchantId: MerchantId, topUpId: UUID): Resource<TopUp> {
        val topUp =
            topUps.find(topUpId)?.takeIf { it.merchantId == merchantId }
                ?: return Resource.error(RampError.TopUpNotFound)
        if (!topUp.state.isOpen || topUp.updatedAt.plus(ASK_EVERY).isAfter(clock.instant())) {
            return Resource.success(topUp)
        }
        return Resource.success(lookUp(topUp))
    }

    /** The merchant's latest top-ups, newest first. */
    suspend fun recent(merchantId: MerchantId, limit: Int): List<TopUp> = topUps.recent(merchantId, limit)

    /**
     * The provider's callback named [reference] on [merchantId]'s address: if it is one of their top-ups that is open,
     * or closed as `UNKNOWN`, the provider is asked. It decides nothing itself.
     */
    suspend fun rung(merchantId: MerchantId, reference: String) {
        val topUpId = runCatching { UUID.fromString(reference.trim()) }.getOrNull() ?: return
        val topUp = topUps.find(topUpId)?.takeIf { it.merchantId == merchantId } ?: return
        if (topUp.state.isOpen || topUp.state == TopUpState.UNKNOWN) lookUp(topUp)
    }

    /** Open top-ups nobody asked about lately, looked up (the job: no callback, nobody watching the portal). */
    suspend fun sweep(): Int {
        val due = topUps.stale(clock.instant().minus(SWEEP_AFTER), LIMIT)
        due.forEach { lookUp(it) }
        return due.size
    }

    /** Whether the merchant's unlocked keys are still needed here: an open top-up is still being looked up. */
    suspend fun needsKeys(merchantId: MerchantId): Boolean = topUps.hasOpen(merchantId)

    /**
     * Asks the provider, and then the balance, what became of [topUp], and records it. A top-up closed as `UNKNOWN`
     * is only ever moved by the provider's own "paid". Without the keys on this node nothing can be asked.
     */
    suspend fun lookUp(topUp: TopUp): TopUp {
        val late = topUp.state == TopUpState.UNKNOWN
        if (!topUp.state.isOpen && !late) return topUp
        val now = clock.instant()
        val keys = sessions.of(topUp.merchantId)?.takeIf { it.provider == topUp.provider }
        val collections = keys?.let { providers.named(it.provider) }
        if (keys == null || collections == null) {
            return if (late) topUp else expireIfDue(topUp.copy(outcome = "no_keys"), now)
        }
        val status = ask(collections, keys.credentials, topUp)
        journal.step("topup looked up", topUp.topUpId.toString(), said(status))
        if (status is TopUpStatus.Paid) return confirm(topUp, TopUpConfirmation.STATUS, status.receipt, null)
        if (late) return topUp

        val risen = risenBalance(topUp)
        if (risen != null) return confirm(topUp, TopUpConfirmation.BALANCE, null, risen)
        return when (status) {
            is TopUpStatus.Failed -> {
                val failure =
                    if (status.reason.uppercase() in
                        CANCELLED_WORDS
                    ) {
                        TopUpFailure.CANCELLED
                    } else {
                        TopUpFailure.FAILED
                    }
                close(topUp, TopUpState.FAILED, failure, "failed:${status.reason}")
            }

            else -> {
                expireIfDue(topUp.copy(outcome = "looked_up:${said(status)}"), now)
            }
        }
    }

    /** Online on this node: their keys. Offline: the PIN opens the account and this node holds it for the top-up. */
    private suspend fun keysFor(merchantId: MerchantId, pin: String?): Resource<UnlockedKeys> {
        sessions.of(merchantId)?.let { return Resource.success(it) }
        if (pin == null) return Resource.error(RampError.TopUpPinRequired)
        val sealed = credentials.find(merchantId) ?: return Resource.error(RampError.MerchantSetupIncomplete)
        return when (val opened = pins.open(merchantId, sealed, pin)) {
            is Resource.Error -> {
                opened
            }

            is Resource.Success -> {
                val keys = UnlockedKeys(sealed.provider, opened.data)
                sessions.hold(merchantId, keys)
                Resource.success(sessions.of(merchantId) ?: keys)
            }
        }
    }

    private suspend fun request(
        topUp: TopUp,
        merchant: Merchant,
        network: Network,
    ): TopUpRequest {
        val callbackBase = settings.of(network).callbackBaseUrl
        return TopUpRequest(
            wallet = walletOf(topUp.target),
            payer = topUp.payer,
            amount = FiatAmount(topUp.amountMinor, RampSettings.FIAT),
            reference = topUp.topUpId.toString(),
            callbackUrl = merchant.callbackKey?.let { Callbacks.url(callbackBase, it) },
            accountId = merchant.payoutAccountId,
        )
    }

    private suspend fun sent(topUp: TopUp, result: TopUpResult): TopUp =
        when (result) {
            is TopUpResult.Accepted -> {
                val accepted =
                    topUp.copy(
                        outcome = "accepted",
                        providerReference = result.receipt.providerReference,
                        checkoutId = result.receipt.checkoutId,
                        updatedAt = clock.instant(),
                    )
                record(topUp, accepted)
            }

            // A prompt may be on the phone: it stays open and is looked up, never sent again.
            TopUpResult.Unknown -> {
                record(topUp, topUp.copy(outcome = "unknown", updatedAt = clock.instant()))
            }

            is TopUpResult.Rejected -> {
                close(topUp, TopUpState.FAILED, failureOf(result.reason), "rejected:${result.reason.name}")
            }

            TopUpResult.Unavailable -> {
                close(topUp, TopUpState.FAILED, TopUpFailure.PROVIDER_UNAVAILABLE, "unavailable")
            }
        }

    private suspend fun confirm(
        topUp: TopUp,
        by: TopUpConfirmation,
        receipt: String?,
        balanceAfter: Long?,
    ): TopUp {
        // Read again after a confirmation by status: it also refreshes what RM2 shows.
        val after =
            balanceAfter ?: merchants
                .find(topUp.merchantId)
                ?.let { balances.read(it) }
                ?.of(topUp.target)
                ?.minor
        val now = clock.instant()
        val confirmed =
            topUp.copy(
                state = TopUpState.CONFIRMED,
                failure = null,
                outcome = "confirmed:${by.name.lowercase()}",
                receipt = receipt ?: topUp.receipt,
                confirmedBy = by,
                balanceAfterMinor = after,
                updatedAt = now,
                closedAt = now,
            )
        journal.step("topup confirmed", topUp.topUpId.toString(), by.name)
        return record(topUp, confirmed)
    }

    private suspend fun close(
        topUp: TopUp,
        state: TopUpState,
        failure: TopUpFailure?,
        outcome: String,
    ): TopUp {
        val now = clock.instant()
        return record(
            topUp,
            topUp.copy(state = state, failure = failure, outcome = outcome, updatedAt = now, closedAt = now),
        )
    }

    /** Still inside [WINDOW]: only touched, so the sweep asks again later. Past it: `UNKNOWN`. */
    private suspend fun expireIfDue(topUp: TopUp, now: Instant): TopUp {
        if (topUp.createdAt.plus(WINDOW).isAfter(now)) return record(topUp, topUp.copy(updatedAt = now))
        journal.step("topup unconfirmed", topUp.topUpId.toString(), topUp.outcome.orEmpty())
        return record(topUp, topUp.copy(state = TopUpState.UNKNOWN, updatedAt = now, closedAt = now))
    }

    /** That wallet's balance now, when it has risen by enough since before the prompt; null otherwise or unread. */
    private suspend fun risenBalance(topUp: TopUp): Long? {
        val before = topUp.balanceBeforeMinor ?: return null
        val merchant = merchants.find(topUp.merchantId) ?: return null
        val now = balances.read(merchant)?.of(topUp.target)?.minor ?: return null
        val needed = topUp.amountMinor * BALANCE_SHARE_PERCENT / PERCENT
        return now.takeIf { it - before >= needed }
    }

    /** Moves the row from the state it was read in; another caller that moved it first wins, and is what we answer. */
    private suspend fun record(before: TopUp, after: TopUp): TopUp {
        if (!topUps.move(after, setOf(before.state))) return topUps.find(before.topUpId) ?: before
        // Settled one way or the other (2026-11-24): the merchant's activity email, once per outcome.
        if (after.state != before.state && after.state != TopUpState.REQUESTED) alerts.topUp(after, clock.instant())
        return after
    }

    private suspend fun ask(
        collections: MobileCollections,
        keys: CollectionCredentials,
        topUp: TopUp,
    ): TopUpStatus {
        val wallet = walletOf(topUp.target)
        return try {
            collections.lookUpTopUp(keys, TopUpLookup(wallet, topUp.topUpId.toString(), topUp.providerReference))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TopUpStatus.Unknown
        }
    }

    /** A call that was cancelled or threw is `Unknown`: the request may have left. */
    private suspend fun send(
        collections: MobileCollections,
        keys: CollectionCredentials,
        request: TopUpRequest,
    ): TopUpResult =
        try {
            collections.requestTopUp(keys, request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            journal.step("topup call failed", request.reference, e::class.simpleName.orEmpty())
            TopUpResult.Unknown
        }

    /** The provider's name for the wallet: never another wallet's, so a top-up can't land elsewhere. */
    private fun walletOf(target: TopUpTarget): TopUpWallet =
        when (target) {
            TopUpTarget.PAYHERO_SERVICE -> TopUpWallet.COLLECTION_FEES
            TopUpTarget.FINOQ_SERVICE -> TopUpWallet.PAYOUT_FEES
            TopUpTarget.FINOQ_PAYMENT -> TopUpWallet.PAYOUT
        }

    private fun failureOf(reason: TopUpRejection): TopUpFailure =
        when (reason) {
            TopUpRejection.SERVICE_BALANCE_LOW -> TopUpFailure.SERVICE_BALANCE_LOW
            TopUpRejection.PAYER_NOT_ALLOWED -> TopUpFailure.PAYER_NOT_ALLOWED
            TopUpRejection.CREDENTIALS_REFUSED -> TopUpFailure.CREDENTIALS_REFUSED
            TopUpRejection.INVALID_REQUEST, TopUpRejection.NOT_SUPPORTED, TopUpRejection.REFUSED -> TopUpFailure.REFUSED
        }

    private fun said(result: TopUpResult): String =
        when (result) {
            is TopUpResult.Rejected -> "Rejected:${result.reason.name}"
            else -> result::class.simpleName.orEmpty()
        }

    private fun said(status: TopUpStatus): String = status::class.simpleName.orEmpty()

    companion object {
        /** KES 10 to KES 150,000 a top-up, whole shillings. */
        const val MIN_SHILLINGS = 10L
        const val MAX_SHILLINGS = 150_000L
        const val MIN_MINOR = MIN_SHILLINGS * FiatAmount.MINOR_PER_UNIT
        const val MAX_MINOR = MAX_SHILLINGS * FiatAmount.MINOR_PER_UNIT

        /** The share of the amount the balance must rise by to count as the top-up having landed. */
        const val BALANCE_SHARE_PERCENT = 90L
        private const val PERCENT = 100L

        /** An M-Pesa prompt lapses on the phone within about a minute; this leaves time for the provider to settle. */
        val WINDOW: Duration = Duration.ofMinutes(5)
        val ASK_EVERY: Duration = Duration.ofSeconds(3)
        private val SWEEP_AFTER: Duration = Duration.ofSeconds(10)
        private const val LIMIT = 50
        private const val SENDING = "sending"
        private val CANCELLED_WORDS = setOf("CANCELLED", "CANCELED", "CANCELLED_BY_USER")
    }
}
