package com.circaso.identity.domain.usecases

import com.circaso.identity.api.AccountActivated
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.DeviceReplaced
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.models.RegisterDeviceCommand
import com.circaso.identity.domain.models.RegisteredDevice
import com.circaso.identity.domain.models.ReplacedDevice
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.AttestationMode
import com.circaso.identity.domain.ports.AttestationVerifier
import com.circaso.identity.domain.ports.DeviceKeyVerifier
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.PhoneProtection
import com.circaso.identity.domain.ports.Transactor
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.identity.domain.repositories.ProviderRepository
import com.circaso.identity.domain.support.IdentityConfig
import com.circaso.identity.domain.support.SessionOpener
import com.circaso.integrations.api.Email
import com.circaso.integrations.api.EmailGateway
import com.circaso.shared.EventBus
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import java.time.Clock

/**
 * I6: registers the phone's key. On sign-up this is the moment the account turns active, `AccountActivated`
 * is written to the outbox and a welcome email goes out; on an active account it replaces the old phone, ends its sessions and writes
 * `DeviceReplaced`. Release builds must pass device attestation (`identity.attestation = ENFORCE`).
 */
class RegisterDevice(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val devices: DeviceRepository,
    private val providers: ProviderRepository,
    private val store: IdentityStore,
    private val cache: IdentityCache,
    private val phones: PhoneProtection,
    private val keys: DeviceKeyVerifier,
    private val attestation: AttestationVerifier,
    private val sessions: SessionOpener,
    private val bus: EventBus,
    private val email: EmailGateway,
    private val config: IdentityConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: RegisterDeviceCommand): Resource<RegisteredDevice> {
        val userId = command.principal.userId
        if (command.deviceId != command.principal.deviceId || !keys.isValid(command.publicKey, command.keyAlgorithm)) {
            return Resource.error(SharedError.Malformed("publicKey", "not a valid key"))
        }
        val account = accounts.find(userId) ?: return Resource.error(SharedError.TokenInvalid)
        if (account.isSupport) return Resource.error(IdentityError.SupportAccountNotOnMobile)
        refusal(account.status)?.let { return Resource.error(it) }
        val phone = account.phone
        if (phone == null ||
            !cache.isPhoneVerified(userId, command.deviceId)
        ) {
            return Resource.error(IdentityError.PhoneMismatch)
        }

        val attested = attestation.verify(command.platform, command.deviceId, command.attestation)
        if (!attested &&
            config.attestation == AttestationMode.ENFORCE
        ) {
            return Resource.error(IdentityError.DeviceAttestationFailed)
        }

        val now = clock.instant()
        val device =
            Device(
                userId,
                command.deviceId,
                command.platform,
                command.model,
                command.appVersion,
                command.publicKey,
                command.keyAlgorithm,
                command.pushToken,
                // A fresh registration starts without one; the iPhone sends its PushKit token after (I9b).
                null,
                DeviceStatus.ACTIVE,
                now,
                now,
            )
        val old = account.activeDeviceId?.takeIf { it != command.deviceId }?.let { devices.find(userId, it) }
        val replacedOld = old?.copy(status = DeviceStatus.REPLACED)

        val activated = account.copy(status = AccountStatus.ACTIVE, activeDeviceId = command.deviceId)
        val previous = sessions.previous(activated, command.deviceId)
        val oldSession = replacedOld?.let { cache.deviceSession(userId, it.deviceId) }

        val events =
            buildList {
                if (account.status == AccountStatus.PENDING_PHONE) {
                    val hints = cache.hints(userId)
                    add(
                        bus.envelope(
                            AccountActivated(
                                userId,
                                phones.hmac(phone),
                                AccountActivated.Hints(hints?.displayName, hints?.avatarUrl),
                            ),
                        ),
                    )
                }
                if (replacedOld !=
                    null
                ) {
                    add(bus.envelope(DeviceReplaced(userId, replacedOld.deviceId, command.deviceId)))
                }
            }
        val newPhone = replacedOld != null
        val securityEvent =
            SecurityEvent(
                userId,
                null,
                if (newPhone) SecurityEventType.NEW_PHONE else SecurityEventType.SIGN_IN,
                command.model,
                null,
            )
        // Everything above was decided on the account as it was read; the lock confirms it is still that version. If
        // a second request for the same account got there first, this one's reading is stale.
        val opened =
            transactor.run("devices.register") {
                if (accounts.lock(userId)?.version != account.version) return@run null
                val opened = sessions.open(activated, command.deviceId, previous)
                store.write(
                    Changes(
                        accounts = listOf(activated),
                        devices = listOfNotNull(device, replacedOld),
                        sessions = opened.ended + opened.issued.session,
                        securityEvents = listOf(securityEvent),
                        events = events,
                    ),
                )
                opened
            } ?: return Resource.error(SharedError.InProgress)
        store.publish(Changes(events = events))
        sessions.afterCommit(activated, opened)
        oldSession?.let { cache.revoke(it) }
        if (newPhone) alertOldPhoneOwner(account.userId, command.model)
        if (account.status == AccountStatus.PENDING_PHONE) welcome(account.userId)

        return Resource.success(
            RegisteredDevice(
                account = activated,
                session = opened.issued.appTokens(),
                agentSession = opened.issued.agentTokens(),
                replacedDevice = replacedOld?.let { ReplacedDevice(it.model, it.lastSeenAt) },
            ),
        )
    }

    /**
     * Only a signing-up or active account may register a phone. Without this, an `onboarding` token handed out before a
     * suspension (it lives 15 minutes and has no session to revoke) and a phone verified in that window could register
     * the phone and turn the suspended account `ACTIVE` again (decision 2026-11-08). The version check under the lock
     * below covers a suspension landing between this read and the write.
     */
    private fun refusal(status: AccountStatus): IdentityError? =
        when (status) {
            AccountStatus.ACTIVE, AccountStatus.PENDING_PHONE -> null
            AccountStatus.SUSPENDED -> IdentityError.AccountSuspended
            AccountStatus.BLOCKED -> IdentityError.AccountBlocked
            AccountStatus.DELETED -> IdentityError.AccountDeleted
            AccountStatus.RECOVERY_PENDING -> IdentityError.RecoveryPending
        }

    /**
     * Best effort, once: the first time an account becomes active, a person who signed in with Google or Apple (the only way
     * we hold an email) gets a short welcome. The key is per account, so a retry cannot send a second one.
     */
    private suspend fun welcome(userId: UserId) {
        val to = providers.links(userId).firstNotNullOfOrNull { it.email } ?: return
        email.send(to, Email.Welcome, idempotencyKey = "welcome-$userId")
    }

    /** Best effort: the owner hears about the new phone by email; a failed email never blocks the sign-in. */
    private suspend fun alertOldPhoneOwner(userId: UserId, newModel: String) {
        val to = providers.links(userId).firstNotNullOfOrNull { it.email } ?: return
        email.send(to, Email.NewPhoneSignIn(newModel))
    }
}
