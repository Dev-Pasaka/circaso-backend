package com.circaso.ramp.domain.repositories

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.Candidate
import com.circaso.ramp.domain.models.DayTotals
import com.circaso.ramp.domain.models.KesBalances
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantSession
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.MerchantType
import com.circaso.ramp.domain.models.MerchantWallet
import com.circaso.ramp.domain.models.OrderState
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.PaymentsIssue
import com.circaso.ramp.domain.models.PayoutIssue
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.RevenueWallet
import com.circaso.ramp.domain.models.SealedCredentials
import com.circaso.ramp.domain.models.Settlement
import com.circaso.ramp.domain.models.StaffOrder
import com.circaso.ramp.domain.models.StaffOrderFilter
import com.circaso.ramp.domain.models.Stamped
import com.circaso.ramp.domain.models.StatementLine
import com.circaso.ramp.domain.models.StatementTotals
import com.circaso.shared.Network
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

interface MerchantRepository {
    suspend fun find(merchantId: MerchantId): Merchant?

    suspend fun byEmail(email: String): Merchant?

    /** The merchant a callback address ending in [callbackKey] belongs to. */
    suspend fun byCallbackKey(callbackKey: String): Merchant?

    /** True when the row was added; false when the email already has an account. */
    suspend fun insert(merchant: Merchant): Boolean

    /** Merchants, newest first, before the id [before]. */
    suspend fun page(before: MerchantId?, limit: Int): List<Merchant>

    /**
     * Every merchant with a float wallet on [network] and [blockchain], whatever their state: the engine's pool. Who
     * of them may take an order is the engine's own filter, which records why each of the others was left out. At
     * most [limit].
     */
    suspend fun pool(
        network: Network,
        blockchain: String,
        limit: Int,
    ): List<Candidate>

    /** Staff's call: the merchant's type and their own order limit (null: the type's default). True when they exist. */
    suspend fun setTier(
        merchantId: MerchantId,
        type: MerchantType,
        orderLimitMinor: Long?,
    ): Boolean

    /** The merchant's own switches: which directions they take orders in. */
    suspend fun setOffers(
        merchantId: MerchantId,
        onRamp: Boolean,
        offRamp: Boolean,
    )

    /** The merchant's row, locked until the transaction ends: reservations and withdrawals take it first. */
    suspend fun lock(merchantId: MerchantId): Merchant?

    suspend fun markAssigned(merchantId: MerchantId, at: Instant)

    /** RM1: the name and phone; a `NEW` merchant becomes `APPLIED`. */
    suspend fun setProfile(
        merchantId: MerchantId,
        displayName: String,
        contactPhone: String,
    )

    /** Moves the merchant to [to] only from one of [from]; true when it moved. */
    suspend fun setState(
        merchantId: MerchantId,
        to: MerchantState,
        from: Set<MerchantState>,
        by: String,
        at: Instant,
    ): Boolean

    suspend fun setCallbackKey(merchantId: MerchantId, callbackKey: String)

    suspend fun setOnlineUntil(merchantId: MerchantId, until: Instant?)

    /** Marks what the merchant must fix at their provider ([issue]), or clears it (null). */
    suspend fun setPaymentsIssue(
        merchantId: MerchantId,
        issue: PaymentsIssue?,
        at: Instant,
    )

    /** Clears the trading window of every merchant whose window ended before [now]; how many. */
    suspend fun endPassedWindows(now: Instant): Int

    /** The merchant's account id at the payout provider (FinoQ's, digits), or none. */
    suspend fun setPayoutAccount(merchantId: MerchantId, accountId: String?)

    /** Marks what the merchant must fix before they are paid out through again ([issue]), or clears it (null). */
    suspend fun setPayoutIssue(
        merchantId: MerchantId,
        issue: PayoutIssue?,
        at: Instant,
    )

    /** Keeps the balances just read at the provider, for staff, who can't read them (the ops process holds no keys). */
    suspend fun recordBalances(merchantId: MerchantId, balances: KesBalances)

    /** The balances last read at the provider. */
    suspend fun balances(merchantId: MerchantId): KesBalances
}

interface MerchantWalletRepository {
    suspend fun find(merchantId: MerchantId, network: Network): MerchantWallet?

    suspend fun ofMerchant(merchantId: MerchantId): List<MerchantWallet>

    /** True when the wallet was recorded; false when the merchant already has one on that network. */
    suspend fun insert(wallet: MerchantWallet, at: Instant): Boolean

    /** Approved merchants with no float wallet on [network] yet. */
    suspend fun approvedWithout(network: Network, limit: Int): List<MerchantId>
}

interface RevenueWalletRepository {
    suspend fun find(network: Network): RevenueWallet?

    suspend fun insert(wallet: RevenueWallet, at: Instant): Boolean
}

interface CredentialRepository {
    suspend fun find(merchantId: MerchantId): SealedCredentials?

    /** Replaces the merchant's one row whole. */
    suspend fun save(
        merchantId: MerchantId,
        sealed: SealedCredentials,
        at: Instant,
    )
}

/** Portal sign-ins, found by the hash of their token. */
interface SessionRepository {
    suspend fun insert(
        tokenHash: ByteArray,
        merchantId: MerchantId,
        now: Instant,
        expiresAt: Instant,
    )

    /** The session while it lasts. */
    suspend fun find(tokenHash: ByteArray, now: Instant): MerchantSession?

    suspend fun extend(tokenHash: ByteArray, expiresAt: Instant)

    suspend fun delete(tokenHash: ByteArray): Boolean
}

/** The daily sweeps of Circaso's share, and the commission rows they collect. */
interface SettlementRepository {
    suspend fun insert(settlement: Settlement)

    suspend fun pending(network: Network, limit: Int): List<Settlement>

    /**
     * Whether the merchant already has a sweep on [network] that stands for the current day: one opened since
     * [since] that didn't fail, or a failed one opened since [failedSince] (so a refused sweep isn't retried at once).
     */
    suspend fun hasRecent(
        merchantId: MerchantId,
        network: Network,
        since: Instant,
        failedSince: Instant,
    ): Boolean

    /** Merchants with a commission on [network] no sweep has taken over yet. */
    suspend fun merchantsOwing(network: Network, limit: Int): List<MerchantId>

    /** Names [settlementId] on every commission of the merchant no sweep has taken over; their total and count. */
    suspend fun stamp(
        merchantId: MerchantId,
        network: Network,
        settlementId: UUID,
    ): Stamped

    /** Records the custodian's transaction for a pending sweep. */
    suspend fun sent(
        settlementId: UUID,
        custodyTxId: String,
        at: Instant,
    )

    /** `PENDING` → `CONFIRMED`, and its commissions are settled; true for the caller that confirmed it. */
    suspend fun confirm(
        settlementId: UUID,
        txHash: String?,
        at: Instant,
    ): Boolean

    /** `PENDING` → `FAILED`, and its commissions are free for the next sweep; true for the caller that failed it. */
    suspend fun fail(
        settlementId: UUID,
        reason: String,
        at: Instant,
    ): Boolean

    /** The merchant's sweeps opened on a day of [period], newest first. */
    suspend fun ofMerchant(
        merchantId: MerchantId,
        network: Network,
        period: Period,
        limit: Int,
    ): List<Settlement>
}

/** A merchant's completed exchanges, read from the order and commission rows together. */
interface StatementRepository {
    /** The exchanges completed in [period], newest first, after [after]. */
    suspend fun lines(
        merchantId: MerchantId,
        network: Network,
        period: Period,
        after: PageCursor?,
        limit: Int,
    ): List<StatementLine>

    /** The totals of every exchange completed in [period]: the sums of what [lines] shows. */
    suspend fun totals(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ): StatementTotals
}

/**
 * A merchant's activity for staff, read from the same order and commission rows as the statement
 * ([StatementRepository]): what didn't complete, the days, and the orders one by one.
 */
interface InsightRepository {
    /** The merchant's orders made in [period] that are not `COMPLETED`, counted by state. */
    suspend fun unfinished(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ): Map<OrderState, Int>

    /** The same orders, counted by the Nairobi day they were made. */
    suspend fun unfinishedByDay(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ): Map<LocalDate, Int>

    /** The exchanges completed in [period], added up per Nairobi day: the statement's totals, a day at a time. */
    suspend fun completedByDay(
        merchantId: MerchantId,
        network: Network,
        period: Period,
    ): List<DayTotals>

    /** The merchant's orders, newest first, after [after], each with its commission when it completed. */
    suspend fun orders(
        filter: StaffOrderFilter,
        after: PageCursor?,
        limit: Int,
    ): List<StaffOrder>
}
