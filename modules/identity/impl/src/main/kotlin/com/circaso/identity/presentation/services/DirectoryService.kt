package com.circaso.identity.presentation.services

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.Principal
import com.circaso.identity.api.PushRegistration
import com.circaso.identity.domain.usecases.GetAccountStatus
import com.circaso.identity.domain.usecases.GetPushTarget
import com.circaso.identity.domain.usecases.ResolvePhones
import com.circaso.identity.domain.usecases.VerifyAccessToken
import com.circaso.identity.domain.usecases.VerifyDeviceSignature
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** What the gateway and the other modules ask of identity through its `api`. */
class DirectoryService(
    private val verifyAccessToken: VerifyAccessToken,
    private val getAccountStatus: GetAccountStatus,
    private val resolvePhones: ResolvePhones,
    private val verifyDeviceSignature: VerifyDeviceSignature,
    private val getPushTarget: GetPushTarget,
) {
    suspend fun verifyToken(token: String): Resource<Principal> = verifyAccessToken(token)

    suspend fun status(userId: UserId): Resource<AccountStatus?> = getAccountStatus(userId)

    suspend fun resolve(phones: Set<PhoneNumber>): Resource<Map<PhoneNumber, UserId>> = resolvePhones(phones)

    suspend fun verifySignature(
        userId: UserId,
        deviceId: DeviceId,
        payload: ByteArray,
        signature: ByteArray,
    ): Resource<Unit> = verifyDeviceSignature(userId, deviceId, payload, signature)

    suspend fun pushTarget(userId: UserId): Resource<PushRegistration?> = getPushTarget(userId)
}
