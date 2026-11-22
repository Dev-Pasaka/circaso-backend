package com.circaso.ramp.domain.repositories

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.Candidate
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantSession
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.MerchantWallet
import com.circaso.ramp.domain.models.PageCursor
import com.circaso.ramp.domain.models.Period
import com.circaso.ramp.domain.models.RevenueWallet
import com.circaso.ramp.domain.models.SealedCredentials
import com.circaso.ramp.domain.models.Settlement
import com.circaso.ramp.domain.models.Stamped
import com.circaso.ramp.domain.models.StatementLine
import com.circaso.ramp.domain.models.StatementTotals
import com.circaso.shared.Network
import java.time.Instant
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
     * Approved merchants with a float wallet on [network] and [blockchain] whose trading window lasts past [until].
     */
    suspend fun candidates(
        network: Network,
        blockchain: String,
        until: Instant,
    ): List<Candidate>

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

    /** Clears the trading window of every merchant whose window ended before [now]; how many. */
    suspend fun endPassedWindows(now: Instant): Int
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
