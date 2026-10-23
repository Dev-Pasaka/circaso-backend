package com.circaso.identity.domain.support

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.NextStep
import com.circaso.identity.domain.models.PhoneVerifiedResult
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.PhoneProtection
import com.circaso.identity.domain.repositories.AccountRepository
import com.circaso.identity.domain.repositories.Changes
import com.circaso.identity.domain.repositories.IdentityStore
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Clock

/**
 * What a proven SIM means, whether it was proven by an SMS code (I4) or a carrier token (I5): on sign-up the
 * number is claimed for the account; on a new phone it must be the account's own number. One account per number,
 * never merged. A number held by an abandoned, unverified account is taken over once its 7-day release notice
 * (I16) has run out, and only by the person who asked for the release.
 */
class PhoneOwnership(
    private val accounts: AccountRepository,
    private val store: IdentityStore,
    private val cache: IdentityCache,
    private val phones: PhoneProtection,
    private val clock: Clock,
) {
    suspend fun confirm(account: Account, phone: PhoneNumber, deviceId: DeviceId): Resource<PhoneVerifiedResult> {
        return when (account.status) {
            AccountStatus.ACTIVE -> {
                if (account.phone != phone) return Resource.error(IdentityError.PhoneMismatch)
                cache.markPhoneVerified(account.userId, deviceId)
                Resource.success(PhoneVerifiedResult(phone.masked(), NextStep.REGISTER_DEVICE))
            }
            AccountStatus.PENDING_PHONE -> claimForSignUp(account, phone, deviceId)
            else -> Resource.error(IdentityError.PhoneMismatch)
        }
    }

    private suspend fun claimForSignUp(account: Account, phone: PhoneNumber, deviceId: DeviceId): Resource<PhoneVerifiedResult> {
        if (!accounts.claimPhone(phone, account.userId)) {
            if (!takeOverIfReleased(phone, account.userId) || !accounts.claimPhone(phone, account.userId)) {
                return Resource.error(IdentityError.PhoneTaken)
            }
        }
        val verified = account.copy(phone = phone)
        store.commit(
            Changes(
                accounts = listOf(verified),
                securityEvents = listOf(SecurityEvent(account.userId, null, SecurityEventType.PHONE_VERIFIED, null, null)),
            ),
        )
        cache.markPhoneVerified(account.userId, deviceId)
        return Resource.success(PhoneVerifiedResult(phone.masked(), NextStep.REGISTER_DEVICE))
    }

    /** Closes the abandoned account and frees its number, if the notice the requester started has run out. */
    private suspend fun takeOverIfReleased(phone: PhoneNumber, requester: UserId): Boolean {
        val release = cache.phoneRelease(phones.hmac(phone)) ?: return false
        if (release.requesterUserId != requester || release.releaseAtEpochMillis > clock.millis()) return false
        val owner = accounts.find(release.ownerUserId) ?: return false
        store.commit(Changes(accounts = listOf(owner.copy(status = AccountStatus.DELETED, phone = null, activeDeviceId = null))))
        accounts.releasePhone(phone)
        return true
    }
}
