package com.circaso.messaging.data.mappers

import com.circaso.messaging.data.entities.KeyBundleRow
import com.circaso.messaging.data.entities.OneTimePreKeyRow
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.models.SignedPreKey
import com.circaso.shared.DeviceId
import com.circaso.shared.UserId

fun KeyBundleRow.toModel() =
    KeyBundle(
        userId = UserId(userId),
        deviceId = DeviceId(deviceId),
        identityKey = identityKey,
        signedPreKey = SignedPreKey(signedPreKeyId, signedPreKey, signedPreKeySignature),
        signedPreKeyAt = signedPreKeyAt,
    )

fun KeyBundle.toRow() =
    KeyBundleRow(
        userId = userId.value,
        deviceId = deviceId.value,
        identityKey = identityKey,
        signedPreKeyId = signedPreKey.keyId,
        signedPreKey = signedPreKey.publicKey,
        signedPreKeySignature = signedPreKey.signature,
        signedPreKeyAt = signedPreKeyAt,
    )

fun OneTimePreKeyRow.toModel() = OneTimePreKey(keyId, publicKey)

fun OneTimePreKey.toRow(userId: UserId) = OneTimePreKeyRow(userId.value, keyId, publicKey)
