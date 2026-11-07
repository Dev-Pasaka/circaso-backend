package com.circaso.share.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.share.api.ShareCode
import com.circaso.share.data.mappers.toContent
import com.circaso.share.data.mappers.toJson
import com.circaso.share.data.tables.LinksTable
import com.circaso.share.domain.models.LinkCursor
import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.models.ShareKind
import com.circaso.share.domain.models.ShareLink
import com.circaso.share.domain.repositories.LinkRepository
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant

/** SH1-SH4, the public page and account removal over `share.links`. Every read hides rows past `expires_at`. */
class LinkRepositoryImpl(private val database: Database) : LinkRepository {
    override suspend fun insert(link: ShareLink, expiresAt: Instant?): Boolean =
        database.tx("links.insert") {
            LinksTable
                .insertIgnore {
                    it[code] = link.code.value
                    it[ownerId] = link.ownerId.value
                    it[kind] = link.kind.name
                    it[snapshot] = link.content?.toJson()
                    it[createdAt] = link.createdAt
                    it[endsAt] = link.endsAt
                    it[revokedAt] = link.revokedAt
                    it[LinksTable.expiresAt] = expiresAt
                }.insertedCount > 0
        }

    override suspend fun find(code: ShareCode, now: Instant): ShareLink? =
        database.tx("links.find") {
            LinksTable
                .selectAll()
                .where { (LinksTable.code eq code.value) and kept(now) }
                .map { it.toLink() }
                .singleOrNull()
        }

    override suspend fun listLive(
        ownerId: UserId,
        now: Instant,
        after: LinkCursor?,
        limit: Int,
    ): List<ShareLink> =
        database.tx("links.list-live") {
            LinksTable
                .selectAll()
                .where { (LinksTable.ownerId eq ownerId.value) and live(now) and before(after) }
                .orderBy(LinksTable.createdAt to SortOrder.DESC, LinksTable.code to SortOrder.DESC)
                .limit(limit)
                .map { it.toLink() }
                .toList()
        }

    override suspend fun revoke(
        code: ShareCode,
        ownerId: UserId,
        now: Instant,
        keepUntil: Instant,
    ): Boolean =
        database.tx("links.revoke") {
            LinksTable.update({
                (LinksTable.code eq code.value) and (LinksTable.ownerId eq ownerId.value) and live(now)
            }) {
                it[snapshot] = null
                it[revokedAt] = now
                it[expiresAt] = keepUntil
            } > 0
        }

    override suspend fun updateContent(code: ShareCode, content: ShareContent): Boolean =
        database.tx("links.update-content") {
            LinksTable.update({
                (LinksTable.code eq code.value) and LinksTable.revokedAt.isNull() and LinksTable.snapshot.isNotNull()
            }) {
                it[snapshot] = content.toJson()
            } > 0
        }

    override suspend fun deleteOwnedBy(ownerId: UserId): Int =
        database.tx("links.delete-owned") {
            LinksTable.deleteWhere { LinksTable.ownerId eq ownerId.value }
        }

    /** Rows the purge job hasn't taken yet. */
    private fun kept(now: Instant): Op<Boolean> = LinksTable.expiresAt.isNull() or (LinksTable.expiresAt greater now)

    /** Links that still open: not revoked, not ended. */
    private fun live(now: Instant): Op<Boolean> =
        LinksTable.revokedAt.isNull() and LinksTable.snapshot.isNotNull() and
            (LinksTable.endsAt.isNull() or (LinksTable.endsAt greater now))

    private fun before(after: LinkCursor?): Op<Boolean> =
        if (after == null) {
            Op.TRUE
        } else {
            (LinksTable.createdAt less after.createdAt) or
                ((LinksTable.createdAt eq after.createdAt) and (LinksTable.code less after.code.value))
        }

    private fun ResultRow.toLink() =
        ShareLink(
            code = ShareCode(this[LinksTable.code]),
            ownerId = UserId(this[LinksTable.ownerId]),
            kind = ShareKind.valueOf(this[LinksTable.kind]),
            content = this[LinksTable.snapshot]?.toContent(),
            createdAt = this[LinksTable.createdAt],
            endsAt = this[LinksTable.endsAt],
            revokedAt = this[LinksTable.revokedAt],
        )
}
