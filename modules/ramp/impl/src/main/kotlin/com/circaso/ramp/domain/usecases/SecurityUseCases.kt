package com.circaso.ramp.domain.usecases

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.errors.RampError
import com.circaso.ramp.domain.models.AssertionChallenge
import com.circaso.ramp.domain.models.CodeSent
import com.circaso.ramp.domain.models.LockOutcome
import com.circaso.ramp.domain.models.MerchantSecurity
import com.circaso.ramp.domain.models.NotificationChoices
import com.circaso.ramp.domain.models.Passkey
import com.circaso.ramp.domain.models.PasskeyAssertion
import com.circaso.ramp.domain.models.PasskeyBook
import com.circaso.ramp.domain.models.RegistrationChallenge
import com.circaso.ramp.domain.models.RemovalProof
import com.circaso.ramp.domain.models.SecurityCodePurpose
import com.circaso.ramp.domain.models.SecuritySettings
import com.circaso.ramp.domain.models.SignInContext
import com.circaso.ramp.domain.models.SignedIn
import com.circaso.ramp.domain.models.StaffSecurity
import com.circaso.ramp.domain.models.StepUpBinding
import com.circaso.ramp.domain.ports.RampTokens
import com.circaso.ramp.domain.repositories.MerchantAlertRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantSecurityRepository
import com.circaso.ramp.domain.repositories.PasskeyRepository
import com.circaso.ramp.domain.repositories.SecuritySettingsRepository
import com.circaso.ramp.domain.repositories.SessionRepository
import com.circaso.ramp.domain.support.AlertSender
import com.circaso.ramp.domain.support.DailySummaries
import com.circaso.ramp.domain.support.MerchantAlerts
import com.circaso.ramp.domain.support.MerchantPasskeys
import com.circaso.ramp.domain.support.MerchantSignIn
import com.circaso.ramp.domain.support.MerchantViews
import com.circaso.ramp.domain.support.StepUp
import com.circaso.ramp.domain.support.WithdrawalLock
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Clock
import java.util.UUID

/** RM-P1: the merchant's passkeys, whether staff require one, and the most they may keep. */
class ListPasskeys(private val passkeys: MerchantPasskeys) {
    suspend operator fun invoke(merchantId: MerchantId): Resource<PasskeyBook> =
        Resource.success(passkeys.book(merchantId))
}

/** RM-P2: emails a code confirming one security change (adding or removing a passkey). */
class RequestSecurityCode(private val passkeys: MerchantPasskeys) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        purpose: SecurityCodePurpose,
        clientAddress: String,
    ): Resource<CodeSent> = passkeys.sendCode(merchantId, purpose, clientAddress)
}

/** RM-P3: options for a new passkey, after a fresh email code and the PIN. */
class StartPasskeyRegistration(private val passkeys: MerchantPasskeys) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        label: String,
        code: String,
        pin: String,
    ): Resource<RegistrationChallenge> = passkeys.startRegistration(merchantId, label, code, pin)
}

/** RM-P4: stores the new passkey the browser made; emails a security alert. */
class FinishPasskeyRegistration(private val passkeys: MerchantPasskeys) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        registrationId: String,
        credentialJson: String,
    ): Resource<Passkey> = passkeys.finishRegistration(merchantId, registrationId, credentialJson)
}

/** RM-P5: removes a passkey, confirmed by another passkey or by a fresh email code and the PIN; emails an alert. */
class RemovePasskey(private val passkeys: MerchantPasskeys) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        passkeyId: UUID,
        proof: RemovalProof,
    ): Resource<Unit> = passkeys.remove(merchantId, passkeyId, proof)
}

/** RM-P6: a challenge confirming one exact sensitive request with a passkey. */
class StartStepUp(private val stepUp: StepUp) {
    suspend operator fun invoke(binding: StepUpBinding): Resource<AssertionChallenge> = stepUp.challenge(binding)
}

/** MA4: a challenge for signing in with a passkey instead of an email code. */
class StartPasskeySignIn(private val stepUp: StepUp) {
    suspend operator fun invoke(): Resource<AssertionChallenge> = Resource.success(stepUp.signInChallenge())
}

/** MA5: signs in with a passkey's answer: a portal session, as MA2 gives; emails a sign-in alert. */
class FinishPasskeySignIn(
    private val stepUp: StepUp,
    private val merchants: MerchantRepository,
    private val sessions: SessionRepository,
    private val tokens: RampTokens,
    private val views: MerchantViews,
    private val alerts: MerchantAlerts,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        assertion: PasskeyAssertion,
        network: Network,
        context: SignInContext,
    ): Resource<SignedIn> {
        val passkey = stepUp.signIn(assertion) ?: return Resource.error(RampError.PasskeySignInFailed)
        val merchant = merchants.find(passkey.merchantId) ?: return Resource.error(RampError.PasskeySignInFailed)
        val now = clock.instant()
        val token = tokens.sessionToken()
        val expiresAt = now.plus(MerchantSignIn.SESSION_LIFETIME)
        sessions.insert(tokens.digest(token), merchant.merchantId, now, expiresAt)
        alerts.signedIn(merchant.merchantId, "passkey (${passkey.label})", context, now)
        return Resource.success(SignedIn(token, expiresAt, views.overview(merchant, network)))
    }
}

/** RM-N1: the merchant's email choices. Security emails are always sent and aren't a choice. */
class GetNotificationChoices(private val security: MerchantSecurityRepository) {
    suspend operator fun invoke(merchantId: MerchantId): Resource<MerchantSecurity> =
        Resource.success(security.find(merchantId))
}

/** RM-N2: changes the merchant's email choices; one left out stays as it is. */
class SetNotificationChoices(private val security: MerchantSecurityRepository, private val clock: Clock) {
    suspend operator fun invoke(merchantId: MerchantId, choices: NotificationChoices): Resource<MerchantSecurity> {
        val current = security.find(merchantId)
        security.setChoices(
            merchantId,
            choices.orderEmails ?: current.orderEmails,
            choices.dailySummary ?: current.dailySummary,
            clock.instant(),
        )
        return Resource.success(security.find(merchantId))
    }
}

/** MA6: "This wasn't me": the signed link from a security email locks the merchant's withdrawals. No session. */
class LockWithdrawals(private val lock: WithdrawalLock) {
    suspend operator fun invoke(token: String): Resource<LockOutcome> = lock.lock(token)
}

/** Staff: lifts a merchant's withdrawals lock (audited on the ops gateway). */
class UnlockWithdrawals(private val lock: WithdrawalLock, private val merchants: MerchantRepository) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        by: String,
        reason: String,
    ): Resource<Unit> {
        merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        return lock.unlock(merchantId, by, reason)
    }
}

/** Staff: one merchant's security: the lock, their passkeys and their recent alerts. */
class GetMerchantSecurity(
    private val merchants: MerchantRepository,
    private val security: MerchantSecurityRepository,
    private val passkeys: PasskeyRepository,
    private val alerts: MerchantAlertRepository,
) {
    suspend operator fun invoke(merchantId: MerchantId): Resource<StaffSecurity> {
        merchants.find(merchantId) ?: return Resource.error(RampError.MerchantNotFound)
        return Resource.success(
            StaffSecurity(security.find(merchantId), passkeys.ofMerchant(merchantId), alerts.recent(merchantId, 50)),
        )
    }
}

/** Staff: the merchant security settings. */
class GetSecuritySettings(private val settings: SecuritySettingsRepository) {
    suspend operator fun invoke(): Resource<SecuritySettings> = Resource.success(settings.get())
}

/** Staff: changes the merchant security settings, version-checked (`ramp.settings_changed`). */
class UpdateSecuritySettings(private val settings: SecuritySettingsRepository, private val clock: Clock) {
    suspend operator fun invoke(
        passkeyRequired: Boolean,
        version: Long,
        by: String,
        reason: String,
    ): Resource<SecuritySettings> {
        val next = SecuritySettings(merchantPasskeyRequired = passkeyRequired, updatedBy = by, reason = reason)
        if (!settings.update(next, version, clock.instant())) return Resource.error(RampError.SettingsChanged)
        return Resource.success(settings.get())
    }
}

/** `ramp.merchant-alerts`: sends the alerts that are due. */
class SendMerchantAlerts(private val sender: AlertSender) {
    suspend operator fun invoke(): Int = sender.run()
}

/** `ramp.daily-summaries`: yesterday's summary for each merchant who asked for one. */
class QueueDailySummaries(private val summaries: DailySummaries) {
    suspend operator fun invoke(): Int = summaries.run()
}
