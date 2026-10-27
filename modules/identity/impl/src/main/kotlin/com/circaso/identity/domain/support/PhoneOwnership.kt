package com.circaso.identity.domain.support

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.AccountStatusChanged
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
import com.circaso.identity.domain.repositories.SessionRepository
import com.circaso.shared.DeviceId
import com.circaso.shared.EventBus
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.envelope
import java.time.Clock

/**
 * What a proven SIM means, whether it was proven by an SMS code (I4) or a carrier token (I5): on sign-up the
 * number is claimed for the account; on a new phone it must be the account's own number. One account per number,
 * never merged. A number held by an abandoned, unverified account is taken over once its 7-day release notice
 * (I16) has run out, and only by the person who asked for the release.
 *
 * The claim is a write through the unique index on `accounts.phone` (`AccountRepository.claimPhone`), inside
 * `Transactor.attempt`: a number another account holds is refused, and everything the operation wrote before is
 * rolled back, so two sign-ups racing for one number can't both win.
 */
class PhoneOwnership(
    private val transactor: Transactor,
    private val accounts: AccountRepository,
    private val sessions: SessionRepository,
    private val store: IdentityStore,
    private val cache: IdentityCache,
    private val phones: PhoneProtection,
    private val bus: EventBus,
    private val config: IdentityConfig,
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
        if (claimed is Resource.Error) return claimed
        cache.markPhoneVerified(userId, deviceId)
        return Resource.success(PhoneVerifiedResult(phone.masked(), NextStep.REGISTER_DEVICE))
    }

    /** Sets the number on the still-pending account, unless another account got it first. */
    private suspend fun claim(userId: UserId, phone: PhoneNumber): Resource<Unit> =
        transactor.attempt("accounts.claim-phone") {
            val account =
                accounts.lock(userId)?.takeIf { it.status == AccountStatus.PENDING_PHONE }
                    ?: return@attempt Resource.error(IdentityError.PhoneTaken)
            claimAndLog(account, phone)
        }

    /**
     * Closes the abandoned account and gives its number to [requester] in one transaction, if the notice the requester
     * started has run out. The closed account is deleted like any other (I14): `deleteAfter` set, its sessions ended
     * and `AccountStatusChanged` written, so it is completed after the grace period. The release lives in Redis, so it
     * is read before the transaction; the event is published and the sessions denied after the commit.
     */
    private suspend fun takeOverIfReleased(
        phone: PhoneNumber,
        holderId: UserId,
        requester: UserId,
    ): Resource<Unit> {
        val release = cache.phoneRelease(phones.hmac(phone))
        if (!isDue(release, holderId, requester)) return Resource.error(IdentityError.PhoneTaken)
        val closed = Changes(events = listOf(bus.envelope(AccountStatusChanged(holderId, AccountStatus.DELETED))))
        val ended =
            transactor.attempt("accounts.take-over-phone") {
                val holder = accounts.lock(holderId)?.takeIf { it.phone == phone }
                val account = accounts.lock(requester)?.takeIf { it.status == AccountStatus.PENDING_PHONE }
                if (holder == null || account == null) return@attempt Resource.error(IdentityError.PhoneTaken)
                val ended = sessions.revokeAll(holderId)
                // The abandoned account lets go of the number first, so the claim below doesn't meet itself.
                store.write(closed.copy(accounts = listOf(close(holder))))
                when (val claimed = claimAndLog(account, phone)) {
                    is Resource.Error -> claimed
                    is Resource.Success -> Resource.success(ended)
                }
            }
        if (ended !is Resource.Success) return Resource.error(IdentityError.PhoneTaken)
        store.publish(closed)
        ended.data.forEach { cache.revoke(it) }
        return Resource.success(Unit)
    }

    /** Inside the transaction: the claim, then its log entry; refused when another account holds the number. */
    private suspend fun claimAndLog(account: Account, phone: PhoneNumber): Resource<Unit> {
        if (!accounts.claimPhone(account, phone)) return Resource.error(IdentityError.PhoneTaken)
        store.write(
            Changes(
                securityEvents =
                    listOf(
                        SecurityEvent(account.userId, null, SecurityEventType.PHONE_VERIFIED, null, null),
                    ),
            ),
        )
        return Resource.success(Unit)
    }

    private fun close(holder: Account) =
        holder.copy(
            status = AccountStatus.DELETED,
            phone = null,
            activeDeviceId = null,
            deleteAfter = clock.instant().plus(config.deletionGrace),
        )

    private fun isDue(
        release: PhoneRelease?,
        holderId: UserId,
        requester: UserId,
    ): Boolean =
        release != null &&
            release.ownerUserId == holderId &&
            release.requesterUserId == requester &&
            release.releaseAtEpochMillis <= clock.millis()
}
