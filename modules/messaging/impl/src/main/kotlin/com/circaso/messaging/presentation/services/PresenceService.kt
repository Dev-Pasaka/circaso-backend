package com.circaso.messaging.presentation.services

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.Presence
import com.circaso.messaging.domain.usecases.GetPresence
import com.circaso.messaging.domain.usecases.SweepPresence
import com.circaso.messaging.domain.usecases.UpdatePresence
import com.circaso.messaging.domain.usecases.WatchPresence
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** Online and last seen: the phone's `presence` and `presence.watch` frames, `GET …/presence` and the sweep job. */
class PresenceService(
    private val updatePresence: UpdatePresence,
    private val watchPresence: WatchPresence,
    private val getPresence: GetPresence,
    private val sweepPresence: SweepPresence,
) {
    suspend fun update(userId: UserId, active: Boolean): Resource<Unit> = updatePresence(userId, active)

    suspend fun watch(userId: UserId, chatId: ChatId): Resource<List<Presence>> = watchPresence(userId, chatId)

    suspend fun get(userId: UserId, chatId: ChatId): Resource<List<Presence>> = getPresence(userId, chatId)

    suspend fun sweep(): Int = sweepPresence()
}
