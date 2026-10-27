package com.circaso.identity.data.repositories

import com.circaso.identity.data.tables.DevicesTable
import com.circaso.identity.domain.models.Device
import com.circaso.identity.domain.models.DeviceStatus
import com.circaso.identity.domain.repositories.DeviceRepository
import com.circaso.infrastructure.api.Database
import com.circaso.shared.DeviceId
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert

class DeviceRepositoryImpl(private val database: Database) : DeviceRepository {
    override suspend fun find(userId: UserId, deviceId: DeviceId): Device? =
        database.tx("devices.find") {
            DevicesTable
                .selectAll()
                .where { (DevicesTable.userId eq userId.value) and (DevicesTable.deviceId eq deviceId.value) }
                .map { it.toDevice() }
                .singleOrNull()
        }

    /** For the store: writes the device as it is now, inside the open transaction. */
    internal suspend fun save(device: Device) {
        database.tx("devices.save") {
            DevicesTable.upsert(DevicesTable.userId, DevicesTable.deviceId) {
                it[userId] = device.userId.value
                it[deviceId] = device.deviceId.value
                it[platform] = device.platform
                it[model] = device.model
                it[appVersion] = device.appVersion
                it[publicKey] = device.publicKey
                it[keyAlgorithm] = device.keyAlgorithm
                it[pushToken] = device.pushToken
                it[status] = device.status.name
                it[registeredAt] = device.registeredAt
                it[lastSeenAt] = device.lastSeenAt
            }
        }
    }
}

private fun ResultRow.toDevice() =
    Device(
        userId = UserId(this[DevicesTable.userId]),
        deviceId = DeviceId(this[DevicesTable.deviceId]),
        platform = this[DevicesTable.platform],
        model = this[DevicesTable.model],
        appVersion = this[DevicesTable.appVersion],
        publicKey = this[DevicesTable.publicKey],
        keyAlgorithm = this[DevicesTable.keyAlgorithm],
        pushToken = this[DevicesTable.pushToken],
        status = DeviceStatus.valueOf(this[DevicesTable.status]),
        registeredAt = this[DevicesTable.registeredAt],
        lastSeenAt = this[DevicesTable.lastSeenAt],
    )
