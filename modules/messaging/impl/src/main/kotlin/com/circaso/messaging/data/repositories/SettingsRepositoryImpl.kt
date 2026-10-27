package com.circaso.messaging.data.repositories

import com.circaso.messaging.data.entities.MessagingSettingsRow
import com.circaso.messaging.data.mappers.toModel
import com.circaso.messaging.data.mappers.toRow
import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.messaging.domain.repositories.SettingsRepository
import com.circaso.shared.UserId
import io.kandra.runtime.repository.KandraSuspendRepository

/** M22 settings on Kandra: one row per user who changed them. */
class SettingsRepositoryImpl(private val rows: KandraSuspendRepository<MessagingSettingsRow>) : SettingsRepository {
    override suspend fun settings(userId: UserId): MessagingSettings =
        rows.findById(userId.value)?.toModel() ?: MessagingSettings()

    override suspend fun save(userId: UserId, settings: MessagingSettings) = rows.save(settings.toRow(userId))
}
