package com.circaso.ramp.presentation.services

import com.circaso.ramp.api.MerchantId
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
import com.circaso.ramp.domain.models.SignInContext
import com.circaso.ramp.domain.models.SignedIn
import com.circaso.ramp.domain.models.StepUpBinding
import com.circaso.ramp.domain.usecases.FinishPasskeyRegistration
import com.circaso.ramp.domain.usecases.FinishPasskeySignIn
import com.circaso.ramp.domain.usecases.GetNotificationChoices
import com.circaso.ramp.domain.usecases.ListPasskeys
import com.circaso.ramp.domain.usecases.LockWithdrawals
import com.circaso.ramp.domain.usecases.RemovePasskey
import com.circaso.ramp.domain.usecases.RequestSecurityCode
import com.circaso.ramp.domain.usecases.SetNotificationChoices
import com.circaso.ramp.domain.usecases.StartPasskeyRegistration
import com.circaso.ramp.domain.usecases.StartPasskeySignIn
import com.circaso.ramp.domain.usecases.StartStepUp
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.util.UUID

/** The merchant portal's security (2026-11-24): passkeys, the step-up, email choices, sign-in by passkey, the lock. */
class SecurityService(
    private val listPasskeys: ListPasskeys,
    private val requestSecurityCode: RequestSecurityCode,
    private val startPasskeyRegistration: StartPasskeyRegistration,
    private val finishPasskeyRegistration: FinishPasskeyRegistration,
    private val removePasskey: RemovePasskey,
    private val startStepUp: StartStepUp,
    private val startPasskeySignIn: StartPasskeySignIn,
    private val finishPasskeySignIn: FinishPasskeySignIn,
    private val getNotificationChoices: GetNotificationChoices,
    private val setNotificationChoices: SetNotificationChoices,
    private val lockWithdrawals: LockWithdrawals,
) {
    suspend fun passkeys(merchantId: MerchantId): Resource<PasskeyBook> = listPasskeys(merchantId)

    suspend fun code(
        merchantId: MerchantId,
        purpose: SecurityCodePurpose,
        clientAddress: String,
    ): Resource<CodeSent> = requestSecurityCode(merchantId, purpose, clientAddress)

    suspend fun registrationOptions(
        merchantId: MerchantId,
        label: String,
        code: String,
        pin: String,
    ): Resource<RegistrationChallenge> = startPasskeyRegistration(merchantId, label, code, pin)

    suspend fun register(
        merchantId: MerchantId,
        registrationId: String,
        credentialJson: String,
    ): Resource<Passkey> = finishPasskeyRegistration(merchantId, registrationId, credentialJson)

    suspend fun remove(
        merchantId: MerchantId,
        passkeyId: UUID,
        proof: RemovalProof,
    ): Resource<Unit> = removePasskey(merchantId, passkeyId, proof)

    suspend fun stepUp(binding: StepUpBinding): Resource<AssertionChallenge> = startStepUp(binding)

    suspend fun signInOptions(): Resource<AssertionChallenge> = startPasskeySignIn()

    suspend fun signIn(
        assertion: PasskeyAssertion,
        network: Network,
        context: SignInContext,
    ): Resource<SignedIn> = finishPasskeySignIn(assertion, network, context)

    suspend fun notifications(merchantId: MerchantId): Resource<MerchantSecurity> = getNotificationChoices(merchantId)

    suspend fun setNotifications(merchantId: MerchantId, choices: NotificationChoices): Resource<MerchantSecurity> =
        setNotificationChoices(merchantId, choices)

    suspend fun lock(token: String): Resource<LockOutcome> = lockWithdrawals(token)
}
