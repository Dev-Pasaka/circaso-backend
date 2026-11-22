package com.circaso.ramp.domain.ports

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.CodeCheck
import com.circaso.ramp.domain.models.Rewrapped
import com.circaso.ramp.domain.models.SealedCredentials
import com.circaso.ramp.domain.models.Unsealed
import java.time.Duration
import java.time.Instant

/**
 * The credential envelope (Ramp → Merchant credentials and the trading session). Sealing and opening need **both**
 * the ramp's master key (a server secret) and the merchant's PIN: a copy of the database alone, or the master key
 * alone, opens nothing. A wrong PIN is told apart from a missing master key.
 */
interface CredentialVault {
    /** Seals [credentials] under [pin] for [merchantId]; null when the server has no master key. */
    suspend fun seal(
        merchantId: MerchantId,
        credentials: CollectionCredentials,
        pin: String,
    ): SealedCredentials?

    suspend fun open(
        merchantId: MerchantId,
        sealed: SealedCredentials,
        pin: String,
    ): Unsealed

    /** The same credentials under [newPin]; only someone who knows [currentPin] can ask. */
    suspend fun rewrap(
        merchantId: MerchantId,
        sealed: SealedCredentials,
        currentPin: String,
        newPin: String,
    ): Rewrapped
}

/**
 * The merchants whose payment keys this node holds unlocked, in its own memory only (never the database, Redis, a log
 * or an event). Redis holds just the fact that a merchant is online and on which node (`rmp:session:{merchantId}`).
 * A node that restarts holds nothing: its merchants are offline until they unlock again.
 *
 * One node is enough for now: a step that needs a merchant's keys runs only on the node that holds them, and nothing
 * routes work to another node yet.
 */
interface TradingSessions {
    /** Keeps [credentials] in this node's memory and marks the merchant online on it until [until]. */
    suspend fun unlock(
        merchantId: MerchantId,
        credentials: CollectionCredentials,
        until: Instant,
    )

    /** The merchant's keys when this node holds them; null otherwise: then nothing is asked of the provider. */
    fun of(merchantId: MerchantId): CollectionCredentials?

    /** Swaps the keys this node holds for the merchant, if it holds any (they stored a new payment account). */
    fun replace(merchantId: MerchantId, credentials: CollectionCredentials)

    /**
     * The merchant is no longer online anywhere (the Redis mark goes). The keys leave this node's memory too unless
     * [keepKeys]: open orders still need them to be verified, and `ramp.end-sessions` drops them once those finish.
     */
    suspend fun end(merchantId: MerchantId, keepKeys: Boolean = false)

    /** Every merchant whose keys this node holds. */
    fun held(): Set<MerchantId>
}

/** Wrong-PIN tries per merchant (`rmp:pin:{merchantId}`): five lock PIN entry for thirty minutes. */
interface PinAttempts {
    /** How long PIN entry stays locked, or null when it isn't. */
    suspend fun lockedFor(merchantId: MerchantId): Duration?

    /** Counts a wrong PIN; the answer is the count including it. */
    suspend fun failed(merchantId: MerchantId): Long

    suspend fun clear(merchantId: MerchantId)
}

/**
 * Portal sign-in codes in Redis, by a digest of the email (never the address itself). A code lasts ten minutes and
 * allows five tries; an email may ask once a minute and three times in ten minutes, an address ten times an hour.
 */
interface SignInCodes {
    /** Counts one request; how long to wait when [emailKey] or [address] has asked too often, else null. */
    suspend fun admit(emailKey: String, address: String): Duration?

    /** Keeps the digest of the code just sent, replacing any before it and its tries. */
    suspend fun keep(emailKey: String, codeDigest: String)

    /** Checks a code. A match uses the code up; a wrong one counts a try. */
    suspend fun check(emailKey: String, codeDigest: String): CodeCheck
}

/** The ramp's random values and digests (a secure random source and SHA-256), kept out of the domain's own code. */
interface RampTokens {
    /** A new portal session token: 32 random bytes, base64url. Shown once; only [digest] of it is stored. */
    fun sessionToken(): String

    /** SHA-256 of [text]. */
    fun digest(text: String): ByteArray

    /** SHA-256 of [text] as lower-case hex, for a Redis key or value. */
    fun hexDigest(text: String): String

    /** Six random digits. */
    fun signInCode(): String

    /** Sixteen random base62 characters: the unguessable end of a merchant's callback address. */
    fun callbackToken(): String
}
