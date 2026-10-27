package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.models.MessagingSettings
import com.circaso.messaging.domain.repositories.SettingsRepository
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * M22 `PATCH /messaging/settings`: read receipts and typing indicators on or off. A field left null stays as it is,
 * so an empty request answers the current settings. Turning read receipts off stops the user's own `receipt read`
 * frames; turning typing off stops their own typing frames.
 */
class UpdateSettings(private val settings: SettingsRepository) {
    suspend operator fun invoke(
        userId: UserId,
        readReceipts: Boolean?,
        typing: Boolean?,
    ): Resource<MessagingSettings> {
        val current = settings.settings(userId)
        val updated =
            current.copy(
                readReceipts = readReceipts ?: current.readReceipts,
                typing = typing ?: current.typing,
            )
        if (updated != current) settings.save(userId, updated)
        return Resource.success(updated)
    }
}
