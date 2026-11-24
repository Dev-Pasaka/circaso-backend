package com.circaso.ramp.domain.ports

import com.circaso.integrations.api.KycDocument
import com.circaso.integrations.api.KycEnvironment
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.AppliedRate
import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.KycView
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.Priced
import com.circaso.ramp.domain.models.RateReading
import com.circaso.ramp.domain.models.ReferenceRate
import com.circaso.ramp.domain.models.Subject
import com.circaso.shared.EventEnvelope
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.LocalDate

/**
 * The domain's transaction boundary (architecture.md → Transactions). Inside a block there are repository calls
 * only: no Redis, no HTTP, no event publishing.
 */
interface Transactor {
    /** Runs [block] in one transaction; [operation] is a string literal naming it in the log line. */
    suspend fun <T> run(operation: String, block: suspend () -> T): T

    /** Like [run]; a block returning `Resource.Error` rolls the whole transaction back, and the error is the answer. */
    suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T>
}

/** The ramp's outbox rows: written in the transaction of the change that caused the event, then published. */
interface RampOutbox {
    suspend fun write(envelope: EventEnvelope)
}

/** Tells the user their order moved: to their socket, or as a push where the gateway words one. */
interface FrameSender {
    suspend fun orderUpdated(order: Order)

    /** Tells the user their identity verification changed on [network], with the whole state the app shows. */
    suspend fun kycUpdated(
        userId: UserId,
        network: Network,
        view: KycView,
    )
}

/** Tells the people on call that something needs a person. Never carries an amount, a phone number or a credential. */
interface RampAlerts {
    fun needsReview(order: Order, reason: String)

    /** Something the ramp does by itself couldn't be done and will be tried again: [what] and [reason], ids only. */
    fun stuck(what: String, reason: String)
}

/** The frames kept for replay after a reconnect. */
interface RealtimeLog {
    suspend fun since(
        userId: UserId,
        seq: Long,
        limit: Int,
    ): List<LoggedFrame>

    suspend fun latest(userId: UserId): Long?
}

data class LoggedFrame(val seq: Long, val json: String)

/**
 * The ramp's identity gate (decision 2026-11-22): whether a subject passed KYC in the provider environment a network
 * trusts, and whether a user is barred. Answered from the ramp's own rows; the provider is never asked here.
 */
interface KycGate {
    suspend fun isVerified(subject: Subject, environment: KycEnvironment): Boolean

    /** Whether [userId] is barred from the ramp: as a user, or as the identity they verified with in [environment]. */
    suspend fun isBlocked(userId: UserId, environment: KycEnvironment): Boolean
}

/**
 * The KYC identity key: `HMAC-SHA256(key, issuing country | document type | document number)` of an approved
 * document, normalised, under a key derived from the ramp's master key. It is what makes "one identity, one account"
 * and a block by identity possible without keeping a document. The document goes in once and is never stored.
 */
interface IdentityKeys {
    /** Whether this server can compute keys at all (it has the master key). */
    val isAvailable: Boolean

    /** The key for [document], or null when the server has no master key. */
    fun of(document: KycDocument): String?
}

/**
 * Prices one side from the other at [feeBps] basis points, the fee inside what the user pays, at the rate staff's
 * pricing settings give the side (decision 2026-11-24: the buy rate for buying, the sell rate for selling; see
 * `RatePolicy`). A side whose rate is paused (no reference, a stale one, an expired manual rate) is
 * `ramp.pricing_unavailable`: never a silent fallback to another rate.
 */
interface Pricing {
    /** Shillings per USDC for [direction] on [network] now, and how that rate was made. */
    suspend fun rate(network: Network, direction: Direction): Resource<AppliedRate>

    /** What [direction] on [network] reads as now: its rate, or why it is paused. */
    suspend fun reading(network: Network, direction: Direction): RateReading

    /** The reference rate as last read (any age), or null when none can be read. */
    suspend fun reference(): ReferenceRate?

    suspend fun price(
        network: Network,
        direction: Direction,
        fiatMinor: Long?,
        assetMinor: Long?,
        feeBps: Int,
    ): Resource<Priced>
}

/** The ramp's counters in Redis: prompts per user per hour, and shillings traded per user per day on a network. */
interface RampCounters {
    /** How many M-Pesa prompts reached this user's phone in the last hour. */
    suspend fun promptsThisHour(userId: UserId): Long

    /** Counts a prompt the provider accepted (one that reached the phone); an attempt that sent nothing never counts. */
    suspend fun countPrompt(userId: UserId): Long

    /** Adds [fiatMinor] to the user's day when it fits under [limitMinor]; false (and nothing added) when it doesn't. */
    suspend fun reserveDaily(
        userId: UserId,
        network: Network,
        day: LocalDate,
        fiatMinor: Long,
        limitMinor: Long,
    ): Boolean

    suspend fun giveBackDaily(
        userId: UserId,
        network: Network,
        day: LocalDate,
        fiatMinor: Long,
    )

    /** Selling's own day (decision 2026-11-23): the shillings a user is paid out, under selling's own limit. */
    suspend fun reserveDailySale(
        userId: UserId,
        network: Network,
        day: LocalDate,
        fiatMinor: Long,
        limitMinor: Long,
    ): Boolean

    suspend fun giveBackDailySale(
        userId: UserId,
        network: Network,
        day: LocalDate,
        fiatMinor: Long,
    )
}

/** The USDC contract on a chain, as the network's settings name it: the only token the ramp counts or sends. */
interface UsdcContracts {
    /** Null when the chain isn't configured with a USDC contract: then nothing is sent on it. */
    fun contractOn(network: Network, blockchain: String): String?
}

/**
 * What is known about a payment prompt whose request failed without an answer (decision 2026-11-23). The engine may
 * ask another merchant only when no prompt can be on the payer's phone, so the two cases must never be confused.
 */
interface PromptTransport {
    /**
     * True only when the request **can't have reached** the provider: its circuit was open, or the connection was
     * never made. A timeout, a dropped answer or a server error is false: a prompt may have gone out.
     */
    fun neverSent(failure: Resource.Error): Boolean
}

/**
 * A merchant's payout balance at their provider as it reads **now** (KES minor units), for routing a sale: given by the
 * caller of the engine, because where that float lives is the payout provider's business. The engine takes off what
 * the merchant's open sales already hold. Null when it can't be read, which is never zero: the merchant is then left
 * out (`FLOAT_UNKNOWN`).
 */
fun interface KesFloat {
    suspend fun of(merchantId: MerchantId): Long?
}

/**
 * One line per step of a payout, a deposit check or a balance read (decision 2026-11-23): which sale or test, what was
 * done and what came back, in our words and with ids only, never an amount, a phone number or a credential. With the
 * integration's own layout lines, what the first real payouts are read from.
 */
interface PayoutJournal {
    fun step(
        what: String,
        id: String,
        outcome: String,
    )
}
