package com.circaso.identity.presentation.services

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.RegisterDeviceCommand
import com.circaso.identity.domain.models.RegisteredDevice
import com.circaso.identity.domain.usecases.RegisterDevice
import com.circaso.identity.domain.usecases.RemoveDevice
import com.circaso.identity.domain.usecases.UpdatePushToken
import com.circaso.identity.domain.usecases.UpdateVoipToken
import com.circaso.shared.Resource

/** This phone: register, push token, VoIP token, remove. */
class DeviceService(
    private val registerDevice: RegisterDevice,
    private val updatePushToken: UpdatePushToken,
    private val removeDevice: RemoveDevice,
    private val updateVoipToken: UpdateVoipToken,
) {
    suspend fun register(command: RegisterDeviceCommand): Resource<RegisteredDevice> = registerDevice(command)

    suspend fun updatePushToken(principal: Principal, token: String): Resource<Unit> =
        updatePushToken.invoke(
            principal,
            token,
        )

    suspend fun updateVoipToken(principal: Principal, token: String): Resource<Unit> =
        updateVoipToken.invoke(principal, token)

    suspend fun remove(principal: Principal): Resource<Unit> = removeDevice(principal)
}
