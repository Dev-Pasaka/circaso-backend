package com.circaso.ramp.domain.ports

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.AssertionOptions
import com.circaso.ramp.domain.models.CreationOptions
import com.circaso.ramp.domain.models.Passkey
import com.circaso.ramp.domain.models.StepUpAction
import java.time.Duration
import java.util.UUID

/** A credential the authenticator just made, checked: what is stored for it. */
class NewCredential(
    val credentialId: ByteArray,
    val publicKeyCose: ByteArray,
    val signCount: Long,
    val transports: List<String>,
    val backupEligible: Boolean,
    val backedUp: Boolean,
)

/** An assertion that checked out against its passkey: the authenticator's new counter. */
data class VerifiedAssertion(val signCount: Long, val backedUp: Boolean)

/** A registration under way: the options for the browser and the state its answer is checked against. */
class PendingCeremony(val options: CreationOptions, val state: String)

/**
 * The WebAuthn ceremonies (a server library behind it, in `data`): the relying party is the merchant portal. Nothing
 * here stores anything; the domain keeps the state and the passkeys.
 */
interface PasskeyCeremonies {
    /** Options for a new passkey of [merchantId], excluding the ones it already has, and the state to finish with. */
    fun registration(
        merchantId: MerchantId,
        userName: String,
        displayName: String,
        exclude: List<Passkey>,
    ): PendingCeremony

    /** Checks the browser's answer against [state]; null when it doesn't check out. */
    fun register(state: String, credentialJson: String): NewCredential?

    /** Options asking for an assertion over [challenge] from one of [allow] (any discoverable passkey when empty). */
    fun assertion(challenge: ByteArray, allow: List<Passkey>): AssertionOptions

    /** The credential id an assertion names; null when it isn't one. */
    fun credentialIdOf(credentialJson: String): ByteArray?

    /** Checks an assertion over [challenge] made by [passkey]; null when it doesn't check out. */
    fun verify(
        challenge: ByteArray,
        credentialJson: String,
        passkey: Passkey,
    ): VerifiedAssertion?
}

/** A registration kept between its options and its answer. */
class KeptRegistration(val merchantId: MerchantId, val label: String, val state: String)

/**
 * A challenge kept between its options and its answer: for whom and for what ([merchantId] and [action] null for a
 * sign-in), the hash of the bound request, and the random nonce the challenge was derived from.
 */
class KeptChallenge(
    val merchantId: MerchantId?,
    val action: StepUpAction?,
    val bindingHash: String,
    val nonce: ByteArray,
)

/** Short-lived, single-use ceremony state (Redis): each is taken once and is gone. */
interface CeremonyStore {
    suspend fun keepRegistration(
        id: String,
        kept: KeptRegistration,
        ttl: Duration,
    )

    suspend fun takeRegistration(id: String): KeptRegistration?

    suspend fun keepChallenge(
        id: String,
        kept: KeptChallenge,
        ttl: Duration,
    )

    suspend fun takeChallenge(id: String): KeptChallenge?
}

/**
 * The "This wasn't me" link of a security alert: a token naming the alert, signed with a key used for nothing else
 * (derived from the ramp's master key), so it can't be made up or moved to another alert. Null without the key.
 */
interface LockLinks {
    fun token(alertId: UUID): String?

    /** The alert a token names, when its signature checks out. */
    fun alertOf(token: String): UUID?
}
