package com.circaso.ramp.domain.support

import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailGateway
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.AlertKind
import com.circaso.ramp.domain.models.AssertionChallenge
import com.circaso.ramp.domain.models.CodeCheck
import com.circaso.ramp.domain.models.CodeSent
import com.circaso.ramp.domain.models.LockOutcome
import com.circaso.ramp.domain.models.OfflineReason
import com.circaso.ramp.domain.models.Passkey
import com.circaso.ramp.domain.models.PasskeyAssertion
import com.circaso.ramp.domain.models.PasskeyBook
import com.circaso.ramp.domain.models.RegistrationChallenge
import com.circaso.ramp.domain.models.RemovalProof
import com.circaso.ramp.domain.models.SecurityCodePurpose
import com.circaso.ramp.domain.models.SecurityView
import com.circaso.ramp.domain.models.StepUpBinding
import com.circaso.ramp.domain.ports.CeremonyStore
import com.circaso.ramp.domain.ports.KeptChallenge
import com.circaso.ramp.domain.ports.KeptRegistration
import com.circaso.ramp.domain.ports.LockLinks
import com.circaso.ramp.domain.ports.PasskeyCeremonies
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.ports.RampTokens
import com.circaso.ramp.domain.ports.SignInCodes
import com.circaso.ramp.domain.ports.Transactor
import com.circaso.ramp.domain.repositories.CredentialRepository
import com.circaso.ramp.domain.repositories.MerchantAlertRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantSecurityRepository
import com.circaso.ramp.domain.repositories.PasskeyRepository
import com.circaso.ramp.domain.repositories.ReservationRepository
import com.circaso.ramp.domain.repositories.SecuritySettingsRepository
import com.circaso.ramp.domain.repositories.SessionRepository
import com.circaso.ramp.domain.repositories.WithdrawalDestinationRepository
import com.circaso.ramp.domain.repositories.WithdrawalRepository
import com.circaso.shared.Resource
import com.circaso.shared.Uuid7
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.util.UUID

/**
 * The passkey step-up (2026-11-24): once a merchant has a passkey, every sensitive action (an address added or
 * removed, a withdrawal, a PIN change, a payment or payout account change, a passkey removed) needs an assertion from
 * one of their passkeys **as well as** the PIN.
 *
 * **Bound to the exact request.** [challenge] hashes the request's [StepUpBinding] (merchant, action and fields;
 * never the PIN or a password) and derives the WebAuthn challenge as `SHA-256(nonce ‖ binding hash)` from a fresh
 * random nonce. [check] takes the kept nonce (once: GETDEL), recomputes the hash from the request actually sent, and
 * verifies the assertion over that challenge. Another amount, address or action, another merchant, a replay, or one
 * older than [CHALLENGE_LIFETIME] is `ramp.step_up_invalid`.
 *
 * **Without a passkey** the PIN alone still works, unless staff set `merchant_passkey_required`
 * (`ramp.passkey_required`).
 */
class StepUp(
    private val passkeys: PasskeyRepository,
    private val settings: SecuritySettingsRepository,
    private val ceremonies: PasskeyCeremonies,
    private val store: CeremonyStore,
    private val tokens: RampTokens,
    private val clock: Clock,
) {
    /** Options for confirming [binding] with a passkey; `ramp.passkey_not_found` without one. */
    suspend fun challenge(binding: StepUpBinding): Resource<AssertionChallenge> {
        val mine = passkeys.ofMerchant(binding.merchantId)
        if (mine.isEmpty()) return Resource.error(RampError.PasskeyNotFound)
        return Resource.success(issue(binding, mine))
    }

    /**
     * Whether [binding] may go ahead: null when it may (confirmed by [assertion], or no passkey and none required),
     * else the error to answer. [except] is a passkey that may not confirm it (the one being removed).
     */
    suspend fun check(
        binding: StepUpBinding,
        assertion: PasskeyAssertion?,
        except: UUID? = null,
    ): Resource.Error? {
        val mine = passkeys.ofMerchant(binding.merchantId).filter { it.passkeyId != except }
        if (mine.isEmpty()) {
            if (passkeys.count(binding.merchantId) > 0) return Resource.Error(RampError.StepUpRequired)
            return if (settings.get().merchantPasskeyRequired) Resource.Error(RampError.PasskeyRequired) else null
        }
        assertion ?: return Resource.Error(RampError.StepUpRequired)
        return if (verify(binding, assertion, mine) != null) null else Resource.Error(RampError.StepUpInvalid)
    }

    /** True when staff require a passkey and [merchantId] has none: sensitive actions wait for one. */
    suspend fun blockedWithoutPasskey(merchantId: MerchantId): Boolean =
        passkeys.count(merchantId) == 0 && settings.get().merchantPasskeyRequired

    /** The passkey that confirmed [binding] with [assertion], or null. */
    suspend fun verify(
        binding: StepUpBinding,
        assertion: PasskeyAssertion,
        allow: List<Passkey>,
    ): Passkey? {
        val kept = store.takeChallenge(assertion.challengeId) ?: return null
        if (kept.merchantId != binding.merchantId || kept.action != binding.action) return null
        val hash = bindingHash(binding)
        if (!MessageDigest.isEqual(hash.toByteArray(), kept.bindingHash.toByteArray())) return null
        return assert(challengeOf(kept.nonce, hash), assertion, allow)
    }

    /** A sign-in challenge: no merchant and no binding; any discoverable passkey may answer. */
    suspend fun signInChallenge(): AssertionChallenge {
        val nonce = tokens.nonce()
        val id = tokens.sessionToken()
        store.keepChallenge(id, KeptChallenge(null, null, SIGN_IN, nonce), CHALLENGE_LIFETIME)
        return AssertionChallenge(id, ceremonies.assertion(challengeOf(nonce, SIGN_IN), emptyList()))
    }

    /** The passkey that answered a sign-in challenge, or null. */
    suspend fun signIn(assertion: PasskeyAssertion): Passkey? {
        val kept = store.takeChallenge(assertion.challengeId) ?: return null
        if (kept.merchantId != null || kept.bindingHash != SIGN_IN) return null
        val credentialId = ceremonies.credentialIdOf(assertion.credentialJson) ?: return null
        val passkey = passkeys.byCredentialId(credentialId) ?: return null
        return assert(challengeOf(kept.nonce, SIGN_IN), assertion, listOf(passkey))
    }

    private suspend fun issue(binding: StepUpBinding, allow: List<Passkey>): AssertionChallenge {
        val nonce = tokens.nonce()
        val hash = bindingHash(binding)
        val id = tokens.sessionToken()
        store.keepChallenge(id, KeptChallenge(binding.merchantId, binding.action, hash, nonce), CHALLENGE_LIFETIME)
        return AssertionChallenge(id, ceremonies.assertion(challengeOf(nonce, hash), allow))
    }

    private suspend fun assert(
        challenge: ByteArray,
        assertion: PasskeyAssertion,
        allow: List<Passkey>,
    ): Passkey? {
        val credentialId = ceremonies.credentialIdOf(assertion.credentialJson) ?: return null
        val passkey = allow.firstOrNull { it.credentialId.contentEquals(credentialId) } ?: return null
        val verified = ceremonies.verify(challenge, assertion.credentialJson, passkey) ?: return null
        passkeys.used(passkey.passkeyId, verified.signCount, verified.backedUp, clock.instant())
        return passkey
    }

    private fun bindingHash(binding: StepUpBinding): String =
        tokens.digest(binding.canonical().toByteArray()).joinToString("") { "%02x".format(it) }

    private fun challengeOf(nonce: ByteArray, hash: String): ByteArray = tokens.digest(nonce + hash.toByteArray())

    companion object {
        val CHALLENGE_LIFETIME: Duration = Duration.ofMinutes(5)

        /** The binding of a sign-in challenge: nothing but "sign in". */
        private const val SIGN_IN = "sign-in"
    }
}

/**
 * Codes that confirm a security change (2026-11-24): emailed, 6 digits, 10 minutes, five tries, one purpose each (a
 * code asked to add a passkey can't remove one). The same Redis limits as sign-in codes, keyed by merchant and purpose.
 */
class SecurityCodes(private val codes: SignInCodes, private val tokens: RampTokens, private val email: EmailGateway) {
    suspend fun send(
        merchantId: MerchantId,
        address: String,
        purpose: SecurityCodePurpose,
        clientAddress: String,
    ): Resource<CodeSent> {
        val key = keyOf(merchantId, purpose)
        codes.admit(key, clientAddress)?.let { wait ->
            return Resource.error(RampError.CodeLocked(wait.seconds.coerceAtLeast(1)))
        }
        val code = tokens.signInCode()
        codes.keep(key, digestOf(merchantId, purpose, code))
        val sent = email.send(address, Email.MerchantSecurityCode(code, MerchantSignIn.CODE_MINUTES, purpose.words))
        return when (sent) {
            is Resource.Error -> Resource.error(RampError.EmailUnavailable)
            is Resource.Success -> Resource.success(CodeSent(MerchantSignIn.RESEND_SECONDS))
        }
    }

    /** Uses up a right code; the error to answer otherwise. */
    suspend fun take(
        merchantId: MerchantId,
        purpose: SecurityCodePurpose,
        code: String,
    ): Resource.Error? =
        when (codes.check(keyOf(merchantId, purpose), digestOf(merchantId, purpose, code))) {
            CodeCheck.MATCH -> null
            CodeCheck.WRONG -> Resource.Error(RampError.CodeInvalid)
            CodeCheck.LOCKED -> Resource.Error(RampError.CodeLocked(MerchantSignIn.RESEND_SECONDS))
        }

    private fun keyOf(merchantId: MerchantId, purpose: SecurityCodePurpose) =
        tokens.hexDigest("security:${purpose.name}:$merchantId")

    private fun digestOf(
        merchantId: MerchantId,
        purpose: SecurityCodePurpose,
        code: String,
    ) = tokens.hexDigest("security:${purpose.name}:$merchantId:$code")
}

/**
 * A merchant's passkeys (2026-11-24): several, each with a label. **Adding one** takes a fresh email code and the PIN
 * (so a stolen session alone can't add an attacker's passkey); **removing one** takes another of the merchant's
 * passkeys, or a fresh email code and the PIN. Both send a security alert.
 */
class MerchantPasskeys(
    private val merchants: MerchantRepository,
    private val passkeys: PasskeyRepository,
    private val credentials: CredentialRepository,
    private val pins: PinGuard,
    private val codes: SecurityCodes,
    private val ceremonies: PasskeyCeremonies,
    private val store: CeremonyStore,
    private val stepUp: StepUp,
    private val settings: SecuritySettingsRepository,
    private val alerts: MerchantAlerts,
    private val transactor: Transactor,
    private val tokens: RampTokens,
    private val clock: Clock,
) {
    suspend fun book(merchantId: MerchantId): PasskeyBook =
        PasskeyBook(passkeys.ofMerchant(merchantId), settings.get().merchantPasskeyRequired, MAX_PASSKEYS)

    suspend fun sendCode(
        merchantId: MerchantId,
        purpose: SecurityCodePurpose,
        clientAddress: String,
    ): Resource<CodeSent> {
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        return codes.send(merchantId, merchant.email, purpose, clientAddress)
    }

    /** The options for a new passkey, after the code and the PIN. */
    suspend fun startRegistration(
        merchantId: MerchantId,
        label: String,
        code: String,
        pin: String,
    ): Resource<RegistrationChallenge> {
        val merchant = merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        val mine = passkeys.ofMerchant(merchantId)
        if (mine.size >= MAX_PASSKEYS) return Resource.error(RampError.PasskeysFull(MAX_PASSKEYS))
        val sealed = credentials.find(merchantId) ?: return Resource.error(RampError.MerchantSetupIncomplete)
        codes.take(merchantId, SecurityCodePurpose.ADD_PASSKEY, code)?.let { return it }
        (pins.open(merchantId, sealed, pin) as? Resource.Error)?.let { return it }
        val pending = ceremonies.registration(merchantId, merchant.email, merchant.displayName, mine)
        val id = tokens.sessionToken()
        store.keepRegistration(id, KeptRegistration(merchantId, label, pending.state), StepUp.CHALLENGE_LIFETIME)
        return Resource.success(RegistrationChallenge(id, pending.options))
    }

    suspend fun finishRegistration(
        merchantId: MerchantId,
        registrationId: String,
        credentialJson: String,
    ): Resource<Passkey> {
        val kept =
            store.takeRegistration(registrationId)?.takeIf { it.merchantId == merchantId }
                ?: return Resource.error(RampError.PasskeyInvalid)
        val made = ceremonies.register(kept.state, credentialJson) ?: return Resource.error(RampError.PasskeyInvalid)
        val now = clock.instant()
        val passkey =
            Passkey(
                passkeyId = Uuid7.generate(now),
                merchantId = merchantId,
                credentialId = made.credentialId,
                publicKeyCose = made.publicKeyCose,
                signCount = made.signCount,
                label = kept.label,
                transports = made.transports,
                backupEligible = made.backupEligible,
                backedUp = made.backedUp,
                createdAt = now,
                lastUsedAt = null,
            )
        val added =
            transactor.run("passkeys.add") {
                passkeys.insert(passkey).also { inserted ->
                    if (inserted) {
                        alerts.passkey(
                            AlertKind.PASSKEY_ADDED,
                            merchantId,
                            passkey.passkeyId,
                            kept.label,
                            now,
                        )
                    }
                }
            }
        return if (added) Resource.success(passkey) else Resource.error(RampError.PasskeyInvalid)
    }

    suspend fun remove(
        merchantId: MerchantId,
        passkeyId: UUID,
        proof: RemovalProof,
    ): Resource<Unit> {
        val passkey =
            passkeys.find(passkeyId)?.takeIf { it.merchantId == merchantId }
                ?: return Resource.error(RampError.PasskeyNotFound)
        when (proof) {
            is RemovalProof.Passkey -> {
                val binding = StepUpBinding.removePasskey(merchantId, passkeyId)
                val others = passkeys.ofMerchant(merchantId).filter { it.passkeyId != passkeyId }
                if (others.isEmpty()) return Resource.error(RampError.StepUpRequired)
                stepUp.verify(binding, proof.assertion, others) ?: return Resource.error(RampError.StepUpInvalid)
            }

            is RemovalProof.CodeAndPin -> {
                val sealed = credentials.find(merchantId) ?: return Resource.error(RampError.MerchantSetupIncomplete)
                codes.take(merchantId, SecurityCodePurpose.REMOVE_PASSKEY, proof.code)?.let { return it }
                (pins.open(merchantId, sealed, proof.pin) as? Resource.Error)?.let { return it }
            }
        }
        val now = clock.instant()
        transactor.run("passkeys.remove") {
            if (passkeys.delete(passkeyId)) {
                alerts.passkey(AlertKind.PASSKEY_REMOVED, merchantId, passkeyId, passkey.label, now)
            }
        }
        return Resource.success(Unit)
    }

    companion object {
        const val MAX_PASSKEYS = 10
    }
}

/**
 * "This wasn't me" (2026-11-24). A security email's link, followed without signing in, **locks the merchant's
 * withdrawals**: every `REQUESTED` withdrawal the ops process hasn't claimed for sending is cancelled and its amount
 * given back to the float; addresses still cooling off are frozen for good; the merchant is taken offline and every
 * portal session ends; the people on call are alerted; the merchant gets an email saying so. Until staff lift it, no
 * address can be added and nothing can be withdrawn.
 *
 * The link works once ([MerchantAlertRepository.useLink]) and for [MerchantAlerts.LINK_LIFETIME]; a second use answers
 * `alreadyLocked` and changes nothing.
 */
class WithdrawalLock(
    private val merchants: MerchantRepository,
    private val links: LockLinks,
    private val alertRows: MerchantAlertRepository,
    private val security: MerchantSecurityRepository,
    private val withdrawals: WithdrawalRepository,
    private val destinations: WithdrawalDestinationRepository,
    private val reservations: ReservationRepository,
    private val sessions: SessionRepository,
    private val presence: Presence,
    private val alerts: MerchantAlerts,
    private val rampAlerts: RampAlerts,
    private val transactor: Transactor,
    private val clock: Clock,
) {
    suspend fun lock(token: String): Resource<LockOutcome> {
        val alertId = links.alertOf(token) ?: return Resource.error(RampError.LockLinkInvalid)
        val alert =
            alertRows.find(alertId)?.takeIf { it.kind.security } ?: return Resource.error(RampError.LockLinkInvalid)
        val now = clock.instant()
        val expires = alert.linkExpiresAt ?: return Resource.error(RampError.LockLinkInvalid)
        if (alert.linkUsedAt != null) return Resource.success(LockOutcome(alert.merchantId, true, 0))
        if (now.isAfter(expires)) return Resource.error(RampError.LockLinkExpired)
        if (!alertRows.useLink(alertId, now)) return Resource.success(LockOutcome(alert.merchantId, true, 0))
        val merchantId = alert.merchantId
        val cancelled =
            transactor.run("security.lock") {
                // The row lock every withdrawal request takes: none is written between this and the lock.
                merchants.lock(merchantId)
                val gone = withdrawals.cancelUnclaimed(merchantId, null, CANCELLED_LOCKED, now)
                gone.forEach { reservations.closeWithdrawal(it.withdrawalId, consumed = false, at = now) }
                destinations.freezeCooling(merchantId, now)
                val newly = security.lock(merchantId, alertId, gone.size, now)
                if (newly) alerts.locked(merchantId, gone.size, now)
                gone.size
            }
        presence.goOffline(merchantId, OfflineReason.LOCKED)
        sessions.deleteAll(merchantId)
        rampAlerts.securityLocked(merchantId, cancelled)
        return Resource.success(LockOutcome(merchantId, false, cancelled))
    }

    /** Staff lift a lock (audited by the ops gateway). Frozen addresses stay frozen. */
    suspend fun unlock(
        merchantId: MerchantId,
        by: String,
        reason: String,
    ): Resource<Unit> =
        if (security.unlock(merchantId, by, reason, clock.instant())) {
            Resource.success(Unit)
        } else {
            Resource.error(RampError.NotLocked)
        }

    companion object {
        const val CANCELLED_LOCKED = "locked"
        const val CANCELLED_REMOVED = "address_removed"
    }
}

/** RM2's `security` card: how many passkeys, whether staff require one, the lock, the email choices. */
class SecurityViews(
    private val passkeys: PasskeyRepository,
    private val security: MerchantSecurityRepository,
    private val settings: SecuritySettingsRepository,
) {
    suspend fun of(merchantId: MerchantId): SecurityView {
        val state = security.find(merchantId)
        return SecurityView(
            passkeys = passkeys.count(merchantId),
            passkeyRequired = settings.get().merchantPasskeyRequired,
            lockedAt = state.lockedAt,
            orderEmails = state.orderEmails,
            dailySummary = state.dailySummary,
        )
    }
}
