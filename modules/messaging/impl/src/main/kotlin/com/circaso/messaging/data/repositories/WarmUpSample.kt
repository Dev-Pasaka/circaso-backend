package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.data.tables.MembersTable
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.r2dbc.select

/**
 * A few real memberships for the start-up warm-up, so it reads chat lists and members that have rows (and maps them)
 * rather than only empty answers. Read-only; nothing is returned to anyone.
 */
class MessagingWarmUpSample(private val database: Database) {
    suspend fun memberships(limit: Int): List<Pair<ChatId, UserId>> =
        database.tx("warm-up.sample") {
            MembersTable
                .select(MembersTable.chatId, MembersTable.userId)
                .limit(limit)
                .map { ChatId(it[MembersTable.chatId]) to UserId(it[MembersTable.userId]) }
                .toList()
        }
}
