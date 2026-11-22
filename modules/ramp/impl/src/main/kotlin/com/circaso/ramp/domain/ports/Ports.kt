package com.circaso.ramp.domain.ports

import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.Order
import com.circaso.ramp.domain.models.Priced
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
 * Whether the user passed KYC for the ramp (decision 2026-11-22). Didit arrives behind this port; until it does,
 * nobody is verified, and only a network whose settings don't require KYC (the testnet) lets anyone trade.
 */
interface KycGate {
    suspend fun isVerified(userId: UserId): Boolean
}

/**
 * Prices one side from the other at [feeBps] basis points, the fee inside what the user pays.
 * TODO(decision: the exchange rate) the day's reference rate with no spread, until where the rate comes from is decided.
 */
interface Pricing {
    suspend fun price(
        direction: Direction,
        fiatMinor: Long?,
        assetMinor: Long?,
        feeBps: Int,
    ): Resource<Priced>
}

/** The ramp's counters in Redis: prompts per user per hour, and shillings traded per user per day on a network. */
interface RampCounters {
    /** Counts one more prompt this hour; the answer is the count including it. */
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
}

/** The USDC contract on a chain, as the network's settings name it: the only token the ramp counts or sends. */
interface UsdcContracts {
    /** Null when the chain isn't configured with a USDC contract: then nothing is sent on it. */
    fun contractOn(network: Network, blockchain: String): String?
}
