package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.data.tables.SettingsTable
import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.messaging.domain.repositories.SettingsRepository
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.singleOrNull
import org.jetbrains.exposed.v1.core.BooleanColumnType
import org.jetbrains.exposed.v1.core.Coalesce
import org.jetbrains.exposed.v1.core.QueryParameter
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsertReturning

/** M22 over `settings`: no row means the defaults, both on. */
class SettingsRepositoryImpl(private val database: Database) : SettingsRepository {
    override suspend fun settings(userId: UserId): MessagingSettings =
        database.tx("settings.find") {
            SettingsTable
                .selectAll()
                .where { SettingsTable.userId eq userId.value }
                .map { it.toSettings() }
                .singleOrNull()
        } ?: MessagingSettings()

    /**
     * One statement, so two changes at once never lose each other's field: `INSERT … ON CONFLICT (user_id) DO UPDATE
     * SET read_receipts = COALESCE(:readReceipts, settings.read_receipts), typing = COALESCE(:typing, settings.typing)
     * RETURNING …`. A first change inserts the defaults with the fields given.
     */
    override suspend fun update(
        userId: UserId,
        readReceipts: Boolean?,
        typing: Boolean?,
    ): MessagingSettings =
        database.tx("settings.update") {
            SettingsTable
                .upsertReturning(
                    SettingsTable.userId,
                    onUpdate = {
                        it[SettingsTable.readReceipts] = Coalesce(parameter(readReceipts), SettingsTable.readReceipts)
                        it[SettingsTable.typing] = Coalesce(parameter(typing), SettingsTable.typing)
                    },
                ) {
                    it[this.userId] = userId.value
                    it[this.readReceipts] = readReceipts ?: DEFAULTS.readReceipts
                    it[this.typing] = typing ?: DEFAULTS.typing
                }.map { it.toSettings() }
                .single()
        }

    private fun parameter(value: Boolean?) = QueryParameter(value, BooleanColumnType())

    private companion object {
        val DEFAULTS = MessagingSettings()
    }
}

private fun ResultRow.toSettings() = MessagingSettings(this[SettingsTable.readReceipts], this[SettingsTable.typing])
