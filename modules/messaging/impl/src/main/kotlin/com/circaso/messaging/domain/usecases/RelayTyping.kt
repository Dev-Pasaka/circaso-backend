package com.circaso.messaging.domain.usecases

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.ports.TypingMarks
import com.circaso.messaging.domain.repositories.SettingsRepository
import com.circaso.messaging.domain.support.DeliveryNotifier
import com.circaso.messaging.domain.support.Membership
import com.circaso.messaging.domain.support.MessagingConfig
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * `messaging.typing` from the phone, relayed to the chat's other members and never stored. Dropped when the sender
 * turned typing indicators off (M22), in a chat of more than 50 people (decision 2026-10-25), and when the same
 * person said "typing" in the last few seconds (`msg:typing`), so a phone that repeats it costs nothing.
 */
class RelayTyping(
    private val membership: Membership,
    private val settings: SettingsRepository,
    private val marks: TypingMarks,
    private val notifier: DeliveryNotifier,
    private val config: MessagingConfig,
) {
    suspend operator fun invoke(
        userId: UserId,
        chatId: ChatId,
        on: Boolean,
    ): Resource<Unit> {
        val members = membership.members(chatId)
        if (userId !in members) return Resource.error(MessagingError.ChatNotFound)
        if (members.size > config.maxTypingMembers) return Resource.success(Unit)
        if (!settings.settings(userId).typing) return Resource.success(Unit)
        if (on && !marks.start(chatId, userId)) return Resource.success(Unit)
        if (!on) marks.stop(chatId, userId)
        notifier.typing(members - userId, chatId, userId, on)
        return Resource.success(Unit)
    }
}
