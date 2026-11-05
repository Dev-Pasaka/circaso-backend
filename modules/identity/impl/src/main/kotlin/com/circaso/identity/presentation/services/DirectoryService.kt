package com.circaso.identity.presentation.services

import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.Principal
import com.circaso.identity.api.PushRegistration
import com.circaso.identity.domain.usecases.GetAccountCreatedAt
import com.circaso.identity.domain.usecases.GetAccountKind
import com.circaso.identity.domain.usecases.GetAccountStatus
import com.circaso.identity.domain.usecases.GetPushTarget
import com.circaso.identity.domain.usecases.ResolvePhones
import com.circaso.identity.domain.usecases.VerifyAccessToken
import com.circaso.identity.domain.usecases.VerifyAccessTokenAlongside
import com.circaso.identity.domain.usecases.VerifyDeviceSignature
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Instant

/** What the gateway and the other modules ask of identity through its `api`. */
class DirectoryService(
    private val verifyAccessToken: VerifyAccessToken,
    private val getAccountStatus: GetAccountStatus,
    private val resolvePhones: ResolvePhones,
    private val verifyDeviceSignature: VerifyDeviceSignature,
    private val getPushTarget: GetPushTarget,
    private val getAccountKind: GetAccountKind,
    private val getAccountCreatedAt: GetAccountCreatedAt,
    private val verifyAccessTokenAlongside: VerifyAccessTokenAlongside,
) {
    suspend fun verifyToken(token: String): Resource<Principal> = verifyAccessToken(token)

    suspend fun <T> verifyTokenAlongside(
        token: String,
        work: suspend (Principal) -> T,
    ): Pair<Resource<Principal>, T?> = verifyAccessTokenAlongside(token, work)

    suspend fun status(userId: UserId): Resource<AccountStatus?> = getAccountStatus(userId)

    suspend fun kind(userId: UserId): Resource<AccountKind?> = getAccountKind(userId)

    suspend fun createdAt(userId: UserId): Resource<Instant?> = getAccountCreatedAt(userId)

    suspend fun resolve(phones: Set<PhoneNumber>): Resource<Map<PhoneNumber, UserId>> = resolvePhones(phones)

    suspend fun verifySignature(
        userId: UserId,
        deviceId: DeviceId,
        payload: ByteArray,
        signature: ByteArray,
    ): Resource<Unit> = verifyDeviceSignature(userId, deviceId, payload, signature)

    suspend fun pushTarget(userId: UserId): Resource<PushRegistration?> = getPushTarget(userId)
}
