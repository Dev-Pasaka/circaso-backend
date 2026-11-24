package com.circaso.identity.api

import com.circaso.shared.DeviceId
import com.circaso.shared.DomainEvent
import com.circaso.shared.InstantSerializer
import com.circaso.shared.Network
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable
import java.time.Instant

/** The stream these are published on is `events:identity`. No raw phone number goes on it: only the HMAC. */

@Serializable
data class AccountActivated(val userId: UserId, val phoneHmac: String, val hints: Hints = Hints()) : DomainEvent {
    override val type: String get() = TYPE

    @Serializable
    data class Hints(val displayName: String? = null, val avatarUrl: String? = null)

    companion object {
        const val TYPE = "identity.AccountActivated"
    }
}

@Serializable
data class PhoneChanged(val userId: UserId, val oldPhoneHmac: String, val newPhoneHmac: String) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "identity.PhoneChanged"
    }
}

@Serializable
data class DeviceReplaced(val userId: UserId, val oldDeviceId: DeviceId, val newDeviceId: DeviceId) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "identity.DeviceReplaced"
    }
}

/** I8: the phone signed out of [sessionId]; the gateway closes that session's socket (decision 2026-10-25). */
@Serializable
data class SignedOut(val userId: UserId, val sessionId: SessionId) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "identity.SignedOut"
    }
}

@Serializable
data class AccountStatusChanged(val userId: UserId, val status: AccountStatus) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "identity.AccountStatusChanged"
    }
}

@Serializable
data class AccountRelinked(val userId: UserId, val provider: String) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "identity.AccountRelinked"
    }
}

@Serializable
data class AccountDeleted(val userId: UserId) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "identity.AccountDeleted"
    }
}

/**
 * Ops created a support account (decision 2026-10-31). It is `ACTIVE` from the start, with no phone, device or
 * login, so it carries no `phoneHmac`; users makes its profile from [displayName] and claims [handle] for it.
 */
@Serializable
data class SupportAccountCreated(val userId: UserId, val displayName: String, val handle: String) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "identity.SupportAccountCreated"
    }
}

/**
 * The person's PIN Vault was opened (2026-11-11): the phone fetched its ciphertext to decrypt the wallet PIN. The wallet
 * pauses outgoing payments for a while after it (`payment_holds`, reason `VAULT_OPENED`), counted from the envelope's
 * `occurredAt`, so whoever opened it can't empty the wallet before the owner hears about it.
 */
@Serializable
data class PinVaultOpened(
    val userId: UserId,
    /** The wallet network whose vault was opened (2026-11-18); payments pause on that network only. */
    val network: Network = Network.TESTNET,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "identity.PinVaultOpened"
    }
}

/**
 * The verified badge (2026-11-24): whether the person's identity is verified now, with Didit's live environment (ramp
 * reports it; a sandbox approval never counts). [verifiedAt] is when, null when not verified. Published only when the
 * answer changed. Never a document, a name or anything read from the ID.
 */
@Serializable
data class IdentityVerificationChanged(
    val userId: UserId,
    val verified: Boolean,
    @Serializable(with = InstantSerializer::class) val verifiedAt: Instant? = null,
) : DomainEvent {
    override val type: String get() = TYPE

    companion object {
        const val TYPE = "identity.IdentityVerificationChanged"
    }
}
