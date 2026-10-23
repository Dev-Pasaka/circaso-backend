package com.circaso.identity.presentation.services

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.RegisterDeviceCommand
import com.circaso.identity.domain.models.RegisteredDevice
import com.circaso.identity.domain.usecases.RegisterDevice
import com.circaso.identity.domain.usecases.RemoveDevice
import com.circaso.identity.domain.usecases.UpdatePushToken
import com.circaso.shared.Resource

/** This phone: register, push token, remove. */
class DeviceService(
    private val registerDevice: RegisterDevice,
    private val updatePushToken: UpdatePushToken,
    private val removeDevice: RemoveDevice,
) {
    suspend fun register(command: RegisterDeviceCommand): Resource<RegisteredDevice> = registerDevice(command)
    suspend fun updatePushToken(principal: Principal, token: String): Resource<Unit> = updatePushToken.invoke(principal, token)
    suspend fun remove(principal: Principal): Resource<Unit> = removeDevice(principal)
}
