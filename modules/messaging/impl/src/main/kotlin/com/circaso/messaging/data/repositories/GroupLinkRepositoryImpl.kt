package com.circaso.messaging.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.messaging.data.tables.GroupLinksTable
import com.circaso.messaging.data.tables.GroupRemovalsTable
import com.circaso.messaging.domain.models.GroupLink
import com.circaso.messaging.domain.models.LinkPreview
import com.circaso.messaging.domain.repositories.GroupLinkRepository
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.time.Instant
import java.util.Base64

/** Group join links over `group_links` (by token, and by group through the live key) and `group_removals`. */
class GroupLinkRepositoryImpl(private val database: Database) : GroupLinkRepository {
    override suspend fun find(token: GroupLinkToken): GroupLink? =
        database.tx("links.find") {
            GroupLinksTable
                .selectAll()
                .where { GroupLinksTable.token eq token.value }
                .map { it.toLink() }
                .singleOrNull()
        }

    override suspend fun liveOf(chatId: ChatId): GroupLink? =
        database.tx("links.live-of") {
            GroupLinksTable
                .selectAll()
                .where { (GroupLinksTable.chatId eq chatId.value) and GroupLinksTable.revokedAt.isNull() }
                .map { it.toLink() }
                .singleOrNull()
        }

    override suspend fun insert(link: GroupLink) {
        val preview = requireNotNull(link.preview) { "a new link carries its preview" }
        database.tx("links.insert") {
            GroupLinksTable.insert {
                it[token] = link.token.value
                it[chatId] = link.chatId.value
                it[createdBy] = link.createdBy.value
                it[name] = preview.name
                it[description] = preview.description
                it[photo] = preview.photo?.let(ENCODER::encodeToString)
                it[createdAt] = link.createdAt
                it[updatedAt] = link.updatedAt
            }
        }
    }

    override suspend fun republish(
        token: GroupLinkToken,
        preview: LinkPreview,
        at: Instant,
    ): Boolean =
        database.tx("links.republish") {
            GroupLinksTable
                .updateReturning(listOf(GroupLinksTable.token), where = {
                    (GroupLinksTable.token eq token.value) and GroupLinksTable.revokedAt.isNull()
                }) {
                    it[name] = preview.name
                    it[description] = preview.description
                    it[photo] = preview.photo?.let(ENCODER::encodeToString)
                    it[updatedAt] = at
                }.toList()
                .isNotEmpty()
        }

    override suspend fun revoke(
        chatId: ChatId,
        at: Instant,
        purgeAt: Instant,
    ): Boolean =
        database.tx("links.revoke") {
            GroupLinksTable
                .updateReturning(listOf(GroupLinksTable.token), where = {
                    (GroupLinksTable.chatId eq chatId.value) and GroupLinksTable.revokedAt.isNull()
                }) {
                    // The published copy goes with the link: nothing of the group stays behind.
                    it[name] = null
                    it[description] = null
                    it[photo] = null
                    it[revokedAt] = at
                    it[expiresAt] = purgeAt
                }.toList()
                .isNotEmpty()
        }

    override suspend fun recordRemoval(
        chatId: ChatId,
        userId: UserId,
        by: UserId,
        at: Instant,
    ) {
        database.tx("links.record-removal") {
            GroupRemovalsTable.insertIgnore {
                it[GroupRemovalsTable.chatId] = chatId.value
                it[GroupRemovalsTable.userId] = userId.value
                it[removedBy] = by.value
                it[removedAt] = at
            }
        }
    }

    override suspend fun wasRemoved(chatId: ChatId, userId: UserId): Boolean =
        database.tx("links.was-removed") {
            GroupRemovalsTable
                .selectAll()
                .where { (GroupRemovalsTable.chatId eq chatId.value) and (GroupRemovalsTable.userId eq userId.value) }
                .map { true }
                .singleOrNull() ?: false
        }

    override suspend fun clearRemoval(chatId: ChatId, userId: UserId) {
        database.tx("links.clear-removal") {
            GroupRemovalsTable.deleteWhere {
                (GroupRemovalsTable.chatId eq chatId.value) and (GroupRemovalsTable.userId eq userId.value)
            }
        }
    }

    private companion object {
        val ENCODER: Base64.Encoder = Base64.getEncoder()
    }
}

private fun ResultRow.toLink(): GroupLink {
    val name = this[GroupLinksTable.name]
    return GroupLink(
        token = GroupLinkToken(this[GroupLinksTable.token]),
        chatId = ChatId(this[GroupLinksTable.chatId]),
        createdBy = UserId(this[GroupLinksTable.createdBy]),
        preview =
            name?.let {
                LinkPreview(
                    name = it,
                    description = this[GroupLinksTable.description],
                    photo = this[GroupLinksTable.photo]?.let(Base64.getDecoder()::decode),
                )
            },
        createdAt = this[GroupLinksTable.createdAt],
        updatedAt = this[GroupLinksTable.updatedAt],
        revokedAt = this[GroupLinksTable.revokedAt],
    )
}
