package com.circaso.identity.presentation.api

import com.circaso.identity.api.AccountDirectory
import com.circaso.identity.api.AccountKind
import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.DeviceSignatures
import com.circaso.identity.api.Principal
import com.circaso.identity.api.PushRegistration
import com.circaso.identity.api.PushRegistrations
import com.circaso.identity.api.TokenVerifier
import com.circaso.identity.presentation.services.DirectoryService
import com.circaso.shared.DeviceId
import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.getOrNull
import java.time.Instant

/** identity:api for the gateway and the other modules. Every call goes through a service, like any other way in. */
class TokenVerifierImpl(private val directory: DirectoryService) : TokenVerifier {
    override suspend fun verify(accessToken: String): Resource<Principal> = directory.verifyToken(accessToken)
}

class AccountDirectoryImpl(private val directory: DirectoryService) : AccountDirectory {
    override suspend fun status(userId: UserId): AccountStatus? = directory.status(userId).getOrNull()

    override suspend fun kind(userId: UserId): AccountKind? = directory.kind(userId).getOrNull()

    override suspend fun resolvePhones(phones: Set<PhoneNumber>): Map<PhoneNumber, UserId> =
        directory.resolve(phones).getOrNull().orEmpty()

    override suspend fun createdAt(userId: UserId): Instant? = directory.createdAt(userId).getOrNull()
}

class DeviceSignaturesImpl(private val directory: DirectoryService) : DeviceSignatures {
    override suspend fun verify(
        userId: UserId,
        deviceId: DeviceId,
        payload: ByteArray,
        signature: ByteArray,
    ): Resource<Unit> = directory.verifySignature(userId, deviceId, payload, signature)
}

class PushRegistrationsImpl(private val directory: DirectoryService) : PushRegistrations {
    override suspend fun pushTarget(userId: UserId): PushRegistration? = directory.pushTarget(userId).getOrNull()
}
