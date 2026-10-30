package com.circaso.identity.api

import com.circaso.shared.DeviceId
import com.circaso.shared.DomainEvent
import com.circaso.shared.UserId
import kotlinx.serialization.Serializable

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
