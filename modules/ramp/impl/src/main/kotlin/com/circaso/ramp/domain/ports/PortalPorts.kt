package com.circaso.ramp.domain.ports

import com.circaso.integrations.api.CollectionCredentials
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.CodeCheck
import com.circaso.ramp.domain.models.OnlineSessionCopy
import com.circaso.ramp.domain.models.Rewrapped
import com.circaso.ramp.domain.models.SealedCredentials
import com.circaso.ramp.domain.models.StoredSession
import com.circaso.ramp.domain.models.UnlockedKeys
import com.circaso.ramp.domain.models.Unsealed
import java.time.Duration
import java.time.Instant

/**
 * The credential envelope (Ramp → Merchant credentials and the trading session). Sealing and opening need **both**
 * the ramp's master key (a server secret) and the merchant's PIN: a copy of the database alone, or the master key
 * alone, opens nothing. A wrong PIN is told apart from a missing master key.
 */
interface CredentialVault {
    /** Seals [credentials], an account at [provider], under [pin]; null when the server has no master key. */
    suspend fun seal(
        merchantId: MerchantId,
        provider: String,
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
 * The merchant's webhook signing secret, sealed under a key derived from the ramp's master key **alone** (2026-11-23).
 * Unlike the payment credentials it needs no PIN: callbacks arrive while the merchant is offline, and the secret only
 * tells FinoQ's callbacks from forgeries (a callback is a doorbell that moves nothing). Bound to the merchant, so a row
 * copied onto another merchant doesn't open.
 */
interface WebhookSecretVault {
    /** The sealed form of [secret]; null when the server has no master key. */
    fun seal(merchantId: MerchantId, secret: String): ByteArray?

    /** The secret, or null when the server has no master key or [sealed] doesn't open (another key, a changed row). */
    fun open(merchantId: MerchantId, sealed: ByteArray): String?
}

/**
 * The encrypted working copy of each online merchant's unlocked keys, in Redis (`rmp:online:{merchantId}`, decision
 * 2026-11-24): what lets a restarted server put its merchants back online without their PIN. AES-256-GCM under a key
 * derived from the ramp's master key alone (HKDF, `ramp/online-session/v1`), bound to the merchant, the network and
 * the session; never plaintext, never logged. It lasts as long as the session may (a window's end, or
 * `online_session_max_days` for an open-ended one) and is deleted the moment the session ends.
 */
interface OnlineSessionCopies {
    /** Writes [copy], kept until [until]; nothing when [until] has passed or the server has no master key. */
    suspend fun save(copy: OnlineSessionCopy, until: Instant)

    suspend fun load(merchantId: MerchantId): StoredSession

    /** Swaps the keys of the merchant's copy, if there is one, keeping its session and its expiry. */
    suspend fun rekey(merchantId: MerchantId, keys: UnlockedKeys)

    suspend fun delete(merchantId: MerchantId)
}

/**
 * The merchants whose payment keys this node holds unlocked, in its memory. Redis holds the fact that a merchant is
 * online and on which node (`rmp:session:{merchantId}`), and, separately and encrypted, the session's working copy
 * ([OnlineSessionCopies]) a restarted node restores them from.
 *
 * One node is enough for now: a step that needs a merchant's keys runs only on the node that holds them, and nothing
 * routes work to another node yet.
 */
interface TradingSessions {
    /**
     * Keeps [keys] in this node's memory and marks the merchant online on it until [until], or, when [until] is null
     * (online until they stop it), for a short time that [renew] extends while the node runs.
     */
    suspend fun unlock(
        merchantId: MerchantId,
        keys: UnlockedKeys,
        until: Instant?,
    )

    /**
     * Keeps [keys] in this node's memory **without** marking the merchant online (a top-up with the PIN while
     * offline, 2026-11-23): they take no order, and `ramp.end-sessions` drops them once nothing open needs them. Keys
     * this node already holds are left as they are.
     */
    fun hold(merchantId: MerchantId, keys: UnlockedKeys)

    /** Extends an open-ended session's mark, if this node holds the merchant's keys. */
    suspend fun renew(merchantId: MerchantId)

    /** The merchant's keys when this node holds them; null otherwise: then nothing is asked of the provider. */
    fun of(merchantId: MerchantId): UnlockedKeys?

    /** Swaps the keys this node holds for the merchant, if it holds any (they stored a new payment account). */
    fun replace(merchantId: MerchantId, keys: UnlockedKeys)

    /**
     * The merchant is no longer online anywhere (the Redis mark goes). The keys leave this node's memory too unless
     * [keepKeys]: open orders still need them to be verified, and `ramp.end-sessions` drops them once those finish.
     */
    suspend fun end(merchantId: MerchantId, keepKeys: Boolean = false)

    /** Every merchant whose keys this node holds. */
    fun held(): Set<MerchantId>

    /**
     * Keeps [keys] restored from a working copy and marks the merchant online on this node as [unlock] does, unless
     * this node already holds keys for them (then nothing changes). True when this call put them there.
     */
    suspend fun adopt(
        merchantId: MerchantId,
        keys: UnlockedKeys,
        until: Instant?,
    ): Boolean

    /** Drops [keys] if they are still the ones held: a restore that lost a race with going offline. */
    fun release(merchantId: MerchantId, keys: UnlockedKeys)
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
