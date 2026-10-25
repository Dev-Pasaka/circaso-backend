package com.circaso.messaging.presentation.services

import com.circaso.messaging.domain.models.AddOneTimeKeysCommand
import com.circaso.messaging.domain.models.FetchedBundle
import com.circaso.messaging.domain.models.KeyStatus
import com.circaso.messaging.domain.models.PublishKeysCommand
import com.circaso.messaging.domain.usecases.AddOneTimeKeys
import com.circaso.messaging.domain.usecases.FetchKeyBundle
import com.circaso.messaging.domain.usecases.GetKeyStatus
import com.circaso.messaging.domain.usecases.PublishKeys
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** The key directory, M1 to M3. */
class KeyService(
    private val publishKeys: PublishKeys,
    private val addOneTimeKeys: AddOneTimeKeys,
    private val getKeyStatus: GetKeyStatus,
    private val fetchKeyBundle: FetchKeyBundle,
) {
    suspend fun publish(command: PublishKeysCommand): Resource<Int> = publishKeys(command)

    suspend fun addOneTime(command: AddOneTimeKeysCommand): Resource<Int> = addOneTimeKeys(command)

    suspend fun status(userId: UserId, deviceId: DeviceId): Resource<KeyStatus> = getKeyStatus(userId, deviceId)

    suspend fun fetch(caller: UserId, target: UserId): Resource<FetchedBundle> = fetchKeyBundle(caller, target)
}
