package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.MessageKind
import com.circaso.messaging.data.tables.ChatsTable
import com.circaso.messaging.data.tables.MembersTable
import com.circaso.messaging.data.tables.MessageRecordsTable
import com.circaso.messaging.domain.models.Chat
import com.circaso.messaging.domain.models.ChatKind
import com.circaso.messaging.domain.models.ChatLine
import com.circaso.messaging.domain.models.ChatSettings
import com.circaso.messaging.domain.models.Member
import com.circaso.messaging.domain.models.Role
import com.circaso.messaging.domain.models.UserChat
import com.circaso.messaging.domain.repositories.ChatRepository
import com.circaso.shared.CircasoJson
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.QueryAlias
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.alias
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteReturning
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.util.UUID

/** Chats and membership over `chats` and `members`; the chat list is `members` read by user through its index. */
class ChatRepositoryImpl(private val database: Database) : ChatRepository {
    override suspend fun chat(chatId: ChatId): Chat? =
        database.tx("chats.find") {
            ChatsTable
                .selectAll()
                .where { ChatsTable.chatId eq chatId.value }
                .map { it.toChat() }
                .singleOrNull()
        }

    override suspend fun lockChat(chatId: ChatId): Chat? =
        database.tx("chats.lock") {
            ChatsTable
                .selectAll()
                .where { ChatsTable.chatId eq chatId.value }
                .forUpdate(ForUpdateOption.ForUpdate)
                .map { it.toChat() }
                .singleOrNull()
        }

    /** `INSERT … ON CONFLICT DO NOTHING`: the chat id, and for a direct chat `chats_direct_pair_key`, is the claim. */
    override suspend fun insertChat(chat: Chat): Boolean =
        database.tx("chats.insert") {
            val pair = chat.directPair?.let { (a, b) -> ordered(a, b) }
            val statement =
                ChatsTable.insertIgnore {
                    it[chatId] = chat.chatId.value
                    it[kind] = chat.kind.name
                    it[directLow] = pair?.first
                    it[directHigh] = pair?.second
                    it[viaSpaceId] = chat.viaSpaceId
                    it[ownerId] = chat.ownerId?.value
                    it[createdBy] = chat.createdBy.value
                    it[createdAt] = chat.createdAt
                    it[version] = chat.version
                    write(it, chat)
                }
            statement.insertedCount > 0
        }

    override suspend fun directChat(a: UserId, b: UserId, viaSpaceId: UUID?): Chat? {
        val (low, high) = ordered(a, b)
        return database.tx("chats.find-direct") {
            ChatsTable
                .selectAll()
                .where {
                    val via = if (viaSpaceId == null) ChatsTable.viaSpaceId.isNull() else ChatsTable.viaSpaceId eq viaSpaceId
                    (ChatsTable.kind eq ChatKind.DIRECT.name) and
                        (ChatsTable.directLow eq low) and
                        (ChatsTable.directHigh eq high) and via
                }.map { it.toChat() }
                .singleOrNull()
        }
    }

    override suspend fun updateChat(old: Chat, new: Chat): Chat? =
        database.tx("chats.update") {
            ChatsTable
                .updateReturning(where = {
                    (ChatsTable.chatId eq old.chatId.value) and (ChatsTable.version eq old.version)
                }) {
                    it[version] = old.version + 1
                    it[ownerId] = new.ownerId?.value
                    write(it, new)
                }.map { it.toChat() }
                .singleOrNull()
        }

    override suspend fun reserveSeats(
        chatId: ChatId,
        seats: Int,
        max: Int,
    ): Chat? =
        database.tx("chats.reserve-seats") {
            ChatsTable
                .updateReturning(where = {
                    (ChatsTable.chatId eq chatId.value) and ((ChatsTable.memberCount + seats) lessEq max)
                }) {
                    it[memberCount] = memberCount + seats
                    it[version] = version + 1L
                }.map { it.toChat() }
                .singleOrNull()
        }

    override suspend fun deleteChat(chatId: ChatId) {
        database.tx("chats.delete") { ChatsTable.deleteWhere { ChatsTable.chatId eq chatId.value } }
    }

    override suspend fun members(chatId: ChatId): List<Member> = members(listOf(chatId))[chatId].orEmpty()

    override suspend fun members(chatIds: Collection<ChatId>): Map<ChatId, List<Member>> {
        if (chatIds.isEmpty()) return emptyMap()
        return database.tx("members.of-chats") {
            MembersTable
                .selectAll()
                .where { MembersTable.chatId inList chatIds.map { it.value } }
                .map { it.toMember() }
                .toList()
                .groupBy { it.chatId }
        }
    }

    override suspend fun member(chatId: ChatId, userId: UserId): Member? =
        database.tx("members.find") {
            MembersTable
                .selectAll()
                .where { (MembersTable.chatId eq chatId.value) and (MembersTable.userId eq userId.value) }
                .map { it.toMember() }
                .singleOrNull()
        }

    override suspend fun addMembers(members: List<Member>) {
        if (members.isEmpty()) return
        database.tx("members.add") {
            MembersTable.batchInsert(members, useMultiRowValues = true, shouldReturnGeneratedValues = false) {
                this[MembersTable.chatId] = it.chatId.value
                this[MembersTable.userId] = it.userId.value
                this[MembersTable.role] = it.role.name
                this[MembersTable.joinedAt] = it.joinedAt
            }
        }
    }

    override suspend fun removeMember(chatId: ChatId, userId: UserId): Member? =
        database.tx("members.remove") {
            MembersTable
                .deleteReturning {
                    (MembersTable.chatId eq chatId.value) and (MembersTable.userId eq userId.value)
                }.map { it.toMember() }
                .singleOrNull()
        }

    override suspend fun setRole(member: Member): Boolean =
        database.tx("members.set-role") {
            MembersTable
                .updateReturning(listOf(MembersTable.userId), where = {
                    (MembersTable.chatId eq member.chatId.value) and (MembersTable.userId eq member.userId.value)
                }) {
                    it[role] = member.role.name
                }.toList()
                .isNotEmpty()
        }

    /**
     * `members JOIN chats LEFT JOIN LATERAL (newest MESSAGE record of the chat LIMIT 1)`: one query for the whole
     * list, each chat's last message read backwards through `message_records_chat_idx`.
     */
    override suspend fun chatList(userId: UserId): List<ChatLine> =
        database.tx("chats.list") {
            val last = newestMessage()
            MembersTable
                .join(ChatsTable, JoinType.INNER, MembersTable.chatId, ChatsTable.chatId)
                .join(last, JoinType.LEFT, lateral = true, additionalConstraint = { Op.TRUE })
                .select(ChatsTable.columns + last[MessageRecordsTable.messageId])
                .where { MembersTable.userId eq userId.value }
                .orderBy(ChatsTable.chatId to SortOrder.ASC)
                .map { ChatLine(it.toChat(), it[last[MessageRecordsTable.messageId]]) }
                .toList()
        }

    override suspend fun chatIdsOf(userId: UserId): List<ChatId> =
        database.tx("chats.ids-of") {
            MembersTable
                .select(MembersTable.chatId)
                .where { MembersTable.userId eq userId.value }
                .map { ChatId(it[MembersTable.chatId]) }
                .toList()
        }

    override suspend fun userChat(userId: UserId, chatId: ChatId): UserChat? =
        database.tx("chats.user-chat") {
            MembersTable
                .join(ChatsTable, JoinType.INNER, MembersTable.chatId, ChatsTable.chatId)
                .select(MembersTable.columns + ChatsTable.kind)
                .where { (MembersTable.chatId eq chatId.value) and (MembersTable.userId eq userId.value) }
                .map {
                    UserChat(
                        userId = userId,
                        chatId = chatId,
                        kind = ChatKind.valueOf(it[ChatsTable.kind]),
                        readUpTo = it[MembersTable.readUpTo],
                        joinedAt = it[MembersTable.joinedAt],
                    )
                }.singleOrNull()
        }

    override suspend fun lastMessageId(chatId: ChatId): UUID? =
        database.tx("chats.last-message") {
            MessageRecordsTable
                .select(MessageRecordsTable.messageId)
                .where {
                    (MessageRecordsTable.chatId eq chatId.value) and
                        (MessageRecordsTable.kind eq MessageKind.MESSAGE.name)
                }.orderBy(MessageRecordsTable.messageId to SortOrder.DESC)
                .limit(1)
                .map { it[MessageRecordsTable.messageId] }
                .singleOrNull()
        }

    override suspend fun moveReadMarker(
        userId: UserId,
        chatId: ChatId,
        upTo: UUID,
    ): Boolean =
        database.tx("members.move-read-marker") {
            MembersTable
                .updateReturning(listOf(MembersTable.userId), where = {
                    (MembersTable.chatId eq chatId.value) and
                        (MembersTable.userId eq userId.value) and
                        (MembersTable.readUpTo.isNull() or (MembersTable.readUpTo less upTo))
                }) {
                    it[readUpTo] = upTo
                }.toList()
                .isNotEmpty()
        }

    override suspend fun shareGroup(a: UserId, b: UserId): Boolean =
        database.tx("chats.share-group") {
            val other = MembersTable.alias("other")
            MembersTable
                .join(other, JoinType.INNER, MembersTable.chatId, other[MembersTable.chatId])
                .join(ChatsTable, JoinType.INNER, MembersTable.chatId, ChatsTable.chatId)
                .select(MembersTable.chatId)
                .where {
                    (MembersTable.userId eq a.value) and
                        (other[MembersTable.userId] eq b.value) and
                        (ChatsTable.kind eq ChatKind.GROUP.name)
                }.limit(1)
                .toList()
                .isNotEmpty()
        }

    /** The newest `MESSAGE` record of the outer query's chat: the right side of M6's lateral join. */
    private fun newestMessage(): QueryAlias =
        MessageRecordsTable
            .select(MessageRecordsTable.messageId)
            .where {
                (MessageRecordsTable.chatId eq ChatsTable.chatId) and
                    (MessageRecordsTable.kind eq MessageKind.MESSAGE.name)
            }.orderBy(MessageRecordsTable.messageId to SortOrder.DESC)
            .limit(1)
            .alias("last_message")
}

/** The columns every rewrite of a chat carries; ids, creation and the version are set by the caller. */
private fun ChatsTable.write(statement: UpdateBuilder<*>, chat: Chat) {
    statement[sealedMeta] = chat.sealedMeta
    statement[metaVersion] = chat.metaVersion
    statement[avatarMediaId] = chat.avatarMediaId
    statement[settings] = CircasoJson.encodeToString(StoredSettings.serializer(), StoredSettings.of(chat.settings))
    statement[memberCount] = chat.memberCount
}

/** `chats.settings`, as JSON. */
@Serializable
private data class StoredSettings(val membersCanAdd: Boolean = false) {
    fun toSettings() = ChatSettings(membersCanAdd)

    companion object {
        fun of(settings: ChatSettings) = StoredSettings(settings.membersCanAdd)
    }
}

/** A direct pair as the table keeps it: the smaller id first, compared as PostgreSQL compares uuids (byte order). */
private fun ordered(a: UserId, b: UserId): Pair<UUID, UUID> =
    if (a.toString() < b.toString()) a.value to b.value else b.value to a.value

private fun ResultRow.toChat(): Chat {
    val low = this[ChatsTable.directLow]
    val high = this[ChatsTable.directHigh]
    return Chat(
        chatId = ChatId(this[ChatsTable.chatId]),
        kind = ChatKind.valueOf(this[ChatsTable.kind]),
        sealedMeta = this[ChatsTable.sealedMeta],
        metaVersion = this[ChatsTable.metaVersion],
        settings = CircasoJson.decodeFromString(StoredSettings.serializer(), this[ChatsTable.settings]).toSettings(),
        memberCount = this[ChatsTable.memberCount],
        createdBy = UserId(this[ChatsTable.createdBy]),
        createdAt = this[ChatsTable.createdAt],
        version = this[ChatsTable.version],
        ownerId = this[ChatsTable.ownerId]?.let(::UserId),
        avatarMediaId = this[ChatsTable.avatarMediaId],
        directPair = if (low != null && high != null) UserId(low) to UserId(high) else null,
        viaSpaceId = this[ChatsTable.viaSpaceId],
    )
}

private fun ResultRow.toMember() =
    Member(
        chatId = ChatId(this[MembersTable.chatId]),
        userId = UserId(this[MembersTable.userId]),
        role = Role.valueOf(this[MembersTable.role]),
        joinedAt = this[MembersTable.joinedAt],
    )
