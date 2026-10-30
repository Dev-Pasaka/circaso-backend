package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.SessionId
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.NextStep
import com.circaso.identity.domain.models.ProviderLink
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.SessionTokens
import com.circaso.identity.domain.models.SignInCommand
import com.circaso.identity.domain.models.SignInResult
import com.circaso.identity.domain.models.Verification
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.TokenIssuer
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.identity.domain.support.DeviceProofs
import com.circaso.identity.domain.support.SessionOpener
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SocialIdentity
import com.circaso.integrations.api.SocialSignIn
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.UserId
import java.time.Clock

/**
 * I2: verifies the Google/Apple token and decides the next step (Identity → Sign-in decision table). A new login
 * creates the account at once as `PENDING_PHONE`, in the same transaction that claims the login through the unique
 * `(provider, subject)` index, so two taps can't create two accounts.
 */
class SignInWithProvider(
    private val transactor: Transactor,
    private val cache: IdentityCache,
    private val social: SocialSignIn,
    private val accounts: AccountRepository,
    private val providers: ProviderRepository,
    private val devices: DeviceRepository,
    private val store: IdentityStore,
    private val sessions: SessionOpener,
    private val proofs: DeviceProofs,
    private val tokens: TokenIssuer,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: SignInCommand): Resource<SignInResult> {
        if (!cache.consumeNonce(command.nonce)) return Resource.error(IdentityError.NonceInvalid)

        val identity =
            when (val verified = social.verify(command.provider, command.idToken, command.nonce)) {
                is Resource.Success -> {
                    verified.data
                }

                is Resource.Error -> {
                    return if (verified.error is IntegrationError.Rejected) {
                        Resource.error(
                            IdentityError.ProviderTokenInvalid,
                        )
                    } else {
                        verified
                    }
                }
            }

        val account = findOrCreate(command, identity)
        if (account.isSupport) return Resource.error(IdentityError.SupportAccountNotOnMobile)
        return when (account.status) {
            AccountStatus.RECOVERY_PENDING -> Resource.error(IdentityError.RecoveryPending)
            AccountStatus.SUSPENDED -> Resource.error(IdentityError.AccountSuspended)
            AccountStatus.BLOCKED -> Resource.error(IdentityError.AccountBlocked)
            AccountStatus.DELETED -> Resource.error(IdentityError.AccountDeleted)
            AccountStatus.PENDING_PHONE -> pendingPhone(account, command)
            AccountStatus.ACTIVE -> active(account, command)
        }
    }

    private suspend fun findOrCreate(command: SignInCommand, identity: SocialIdentity): Account {
        providers.ownerOf(command.provider, identity.subject)?.let { known ->
            return checkNotNull(accounts.find(known)) { "A login is linked to an account that doesn't exist" }
        }

        val now = clock.instant()
        val account = Account(UserId.new(), null, AccountStatus.PENDING_PHONE, null, Verification.NONE, 1, now)
        val link = ProviderLink(account.userId, command.provider, identity.subject, identity.email, now)
        // Two taps race here: the unique login index lets one claim land; the other writes nothing and adopts it.
        val created =
            transactor.run("accounts.sign-up") {
                if (!providers.claim(link)) return@run false
                accounts.create(account)
                store.write(Changes(securityEvents = listOf(linked(account, command))))
                true
            }
        if (!created) return winnerOf(command, identity)
        cache.saveHints(account.userId, command.appleName ?: identity.displayName, identity.pictureUrl)
        return account
    }

    private suspend fun winnerOf(command: SignInCommand, identity: SocialIdentity): Account {
        val winner = checkNotNull(providers.ownerOf(command.provider, identity.subject)) { "A lost claim has no owner" }
        return checkNotNull(accounts.find(winner)) { "A login is linked to an account that doesn't exist" }
    }

    private fun linked(account: Account, command: SignInCommand) =
        SecurityEvent(
            account.userId,
            null,
            SecurityEventType.PROVIDER_LINKED,
            command.device.model,
            command.provider.name,
        )

    private suspend fun pendingPhone(account: Account, command: SignInCommand): Resource<SignInResult> {
        val next =
            if (account.phone != null && cache.isPhoneVerified(account.userId, command.device.deviceId)) {
                NextStep.REGISTER_DEVICE
            } else {
                NextStep.VERIFY_PHONE
            }
        return Resource.success(onboarding(account, command, next))
    }

    private suspend fun active(account: Account, command: SignInCommand): Resource<SignInResult> {
        val registered =
            account.activeDeviceId
                ?.takeIf {
                    it == command.device.deviceId
                }?.let { devices.find(account.userId, it) }
        val proof = command.proof
        if (proof == null || !proofs.valid(registered, DeviceProofs.signInPayload(command.nonce, proof), proof)) {
            return reverify(account, command)
        }

        val previous = sessions.previous(account, command.device.deviceId)
        val opened =
            transactor.run("sessions.sign-in") {
                // The phone was checked against the account read above; it must still be the active one.
                val current =
                    accounts
                        .lock(account.userId)
                        ?.takeIf { it.status == AccountStatus.ACTIVE && it.activeDeviceId == command.device.deviceId }
                        ?: return@run null
                val opened = sessions.open(current, command.device.deviceId, previous)
                store.write(
                    Changes(
                        sessions = opened.ended + opened.issued.session,
                        securityEvents =
                            listOf(
                                SecurityEvent(current.userId, null, SecurityEventType.SIGN_IN, registered?.model, null),
                            ),
                    ),
                )
                opened
            } ?: return reverify(account, command)
        sessions.afterCommit(account, opened)
        return Resource.success(
            SignInResult(
                NextStep.DONE,
                account.phone?.masked(),
                opened.issued.appTokens(),
                opened.issued.agentTokens(),
            ),
        )
    }

    private suspend fun reverify(account: Account, command: SignInCommand): Resource<SignInResult> =
        Resource.success(onboarding(account, command, NextStep.REVERIFY_PHONE))

    private fun onboarding(
        account: Account,
        command: SignInCommand,
        next: NextStep,
    ): SignInResult {
        val token =
            tokens.issue(
                account.userId,
                command.device.deviceId,
                SessionId.new(),
                Scope.ONBOARDING,
                account.sessionVersion,
            )
        return SignInResult(
            next,
            account.phone?.masked(),
            SessionTokens(token.token, null, token.expiresInSeconds, Scope.ONBOARDING.wire),
            null,
        )
    }
}
