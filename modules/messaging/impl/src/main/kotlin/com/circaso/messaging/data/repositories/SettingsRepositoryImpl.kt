package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.data.tables.SettingsTable
import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.messaging.domain.repositories.SettingsRepository
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert

/** M22 over `settings`: no row means the defaults, both on. */
class SettingsRepositoryImpl(private val database: Database) : SettingsRepository {
    override suspend fun settings(userId: UserId): MessagingSettings =
        database.tx("settings.find") {
            SettingsTable
                .selectAll()
                .where { SettingsTable.userId eq userId.value }
                .map { MessagingSettings(it[SettingsTable.readReceipts], it[SettingsTable.typing]) }
                .singleOrNull()
        } ?: MessagingSettings()

    override suspend fun save(userId: UserId, settings: MessagingSettings) {
        database.tx("settings.save") {
            SettingsTable.upsert(SettingsTable.userId) {
                it[this.userId] = userId.value
                it[readReceipts] = settings.readReceipts
                it[typing] = settings.typing
            }
        }
    }
}
