package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.data.tables.ChatsTable
import com.circaso.messaging.data.tables.MembersTable
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.GroupKey
import com.circaso.messaging.domain.repositories.GroupReportRepository
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll

/** Staff reads over `chats` and `members` (decision 2026-11-08); every one is served by an index, never a scan. */
class GroupReportRepositoryImpl(private val database: Database) : GroupReportRepository {
    override suspend fun newest(after: GroupKey?, limit: Int): List<Chat> =
        database.tx("groups.newest") {
            // chats_groups_newest_idx: (created_at DESC, chat_id DESC) WHERE kind = 'GROUP'.
            ChatsTable
                .selectAll()
                .where { isGroup() and (after?.let(::before) ?: Op.TRUE) }
                .orderBy(ChatsTable.createdAt to SortOrder.DESC, ChatsTable.chatId to SortOrder.DESC)
                .limit(limit)
                .map { it.toChat() }
                .toList()
        }

    override suspend fun groups(ids: Set<ChatId>): List<Chat> =
        database.tx("groups.by-ids") {
            ChatsTable
                .selectAll()
                .where { (ChatsTable.chatId inList ids.map { it.value }) and isGroup() }
                .map { it.toChat() }
                .toList()
        }

    override suspend fun groupsOf(userId: UserId, limit: Int): List<Chat> =
        database.tx("groups.of-user") {
            // The person's member rows through members_user_idx, each chat by its primary key.
            MembersTable
                .join(ChatsTable, JoinType.INNER, MembersTable.chatId, ChatsTable.chatId)
                .select(ChatsTable.columns)
                .where { (MembersTable.userId eq userId.value) and isGroup() }
                .orderBy(ChatsTable.createdAt to SortOrder.DESC, ChatsTable.chatId to SortOrder.DESC)
                .limit(limit)
                .map { it.toChat() }
                .toList()
        }

    override suspend fun count(): Long =
        database.tx("groups.count") {
            val count = ChatsTable.chatId.count()
            ChatsTable
                .select(count)
                .where { isGroup() }
                .map { it[count] }
                .single()
        }

    private fun isGroup(): Op<Boolean> = ChatsTable.kind eq ChatKind.GROUP.name

    /** Groups after [key] newest first; the leading `<=` makes it a range of the index rather than a filter. */
    private fun before(key: GroupKey): Op<Boolean> =
        (ChatsTable.createdAt lessEq key.createdAt) and
            (
                (ChatsTable.createdAt less key.createdAt) or
                    ((ChatsTable.createdAt eq key.createdAt) and (ChatsTable.chatId less key.chatId.value))
            )
}
