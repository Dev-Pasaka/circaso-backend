package com.circaso.identity.domain.support

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.domain.errors.ClaimLost
import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.Account
import com.circaso.identity.domain.models.NextStep
import com.circaso.identity.domain.models.PhoneVerifiedResult
import com.circaso.identity.domain.models.SecurityEvent
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.ports.IdentityCache
import com.circaso.identity.domain.ports.PhoneProtection
import com.circaso.identity.domain.ports.PhoneRelease
import com.circaso.identity.domain.ports.Transactor
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
 *
 * The claim is the write itself: setting `accounts.phone` inside one transaction, where the unique index refuses a
 * number another account holds (`ClaimLost`), so two sign-ups racing for one number can't both win.
 */
class PhoneOwnership(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val store: IdentityStore,
    private val cache: IdentityCache,
    private val phones: PhoneProtection,
    private val clock: Clock,
) {
    suspend fun confirm(
        account: Account,
        phone: PhoneNumber,
        deviceId: DeviceId,
    ): Resource<PhoneVerifiedResult> {
        return when (account.status) {
            AccountStatus.ACTIVE -> {
                if (account.phone != phone) return Resource.error(IdentityError.PhoneMismatch)
                cache.markPhoneVerified(account.userId, deviceId)
                Resource.success(PhoneVerifiedResult(phone.masked(), NextStep.REGISTER_DEVICE))
            }

            AccountStatus.PENDING_PHONE -> {
                claimForSignUp(account.userId, phone, deviceId)
            }

            else -> {
                Resource.error(IdentityError.PhoneMismatch)
            }
        }
    }

    private suspend fun claimForSignUp(
        userId: UserId,
        phone: PhoneNumber,
        deviceId: DeviceId,
    ): Resource<PhoneVerifiedResult> {
        val holder = accounts.findByPhone(phone)
        val claimed =
            when {
                holder == null || holder.userId == userId -> claim(userId, phone)
                else -> takeOverIfReleased(phone, holder.userId, userId)
            }
        if (!claimed) return Resource.error(IdentityError.PhoneTaken)
        cache.markPhoneVerified(userId, deviceId)
        return Resource.success(PhoneVerifiedResult(phone.masked(), NextStep.REGISTER_DEVICE))
    }

    /** Sets the number on the still-pending account; false when another account got it first. */
    private suspend fun claim(userId: UserId, phone: PhoneNumber): Boolean =
        try {
            transactor.run("accounts.claim-phone") {
                val account = accounts.lock(userId)?.takeIf { it.status == AccountStatus.PENDING_PHONE }
                if (account != null) store.write(verified(account, phone))
                account != null
            }
        } catch (_: ClaimLost) {
            false
        }

    /**
     * Closes the abandoned account and gives its number to [requester] in one transaction, if the notice the requester
     * started has run out. The release lives in Redis, so it is read before the transaction.
     */
    private suspend fun takeOverIfReleased(
        phone: PhoneNumber,
        holderId: UserId,
        requester: UserId,
    ): Boolean {
        val release = cache.phoneRelease(phones.hmac(phone))
        if (!isDue(release, holderId, requester)) return false
        return try {
            transactor.run("accounts.take-over-phone") {
                val holder = accounts.lock(holderId)?.takeIf { it.phone == phone }
                val account = accounts.lock(requester)?.takeIf { it.status == AccountStatus.PENDING_PHONE }
                if (holder == null || account == null) return@run false
                val closed = holder.copy(status = AccountStatus.DELETED, phone = null, activeDeviceId = null)
                // The abandoned account lets go of the number first, so the claim below doesn't meet itself.
                store.write(Changes(accounts = listOf(closed)))
                store.write(verified(account, phone))
                true
            }
        } catch (_: ClaimLost) {
            false
        }
    }

    private fun isDue(
        release: PhoneRelease?,
        holderId: UserId,
        requester: UserId,
    ): Boolean =
        release != null &&
            release.ownerUserId == holderId &&
            release.requesterUserId == requester &&
            release.releaseAtEpochMillis <= clock.millis()

    private fun verified(account: Account, phone: PhoneNumber) =
        Changes(
            accounts = listOf(account.copy(phone = phone)),
            securityEvents =
                listOf(
                    SecurityEvent(account.userId, null, SecurityEventType.PHONE_VERIFIED, null, null),
                ),
        )
}
