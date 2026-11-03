package com.circaso.status.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import com.circaso.status.data.tables.MediaTable
import com.circaso.status.domain.models.MediaKind
import com.circaso.status.domain.models.MediaState
import com.circaso.status.domain.models.MomentMedia
import com.circaso.status.domain.repositories.MediaRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.time.Instant
import java.util.UUID

/** ST1-ST3, ST8 and the sweep over `media`. */
class MediaRepositoryImpl(private val database: Database) : MediaRepository {
    override suspend fun insert(media: MomentMedia) {
        database.tx("media.insert") {
            MediaTable.insert {
                it[mediaId] = media.mediaId
                it[posterId] = media.posterId.value
                it[kind] = media.kind.name
                it[sizeBytes] = media.sizeBytes
                it[state] = media.state.name
                it[momentId] = media.momentId?.value
                it[createdAt] = media.createdAt
                it[expiresAt] = media.expiresAt
            }
        }
    }

    override suspend fun find(mediaId: UUID): MomentMedia? =
        database.tx("media.find") {
            MediaTable
                .selectAll()
                .where { MediaTable.mediaId eq mediaId }
                .map { it.toMedia() }
                .singleOrNull()
        }

    override suspend fun markReady(mediaId: UUID): Boolean =
        database.tx("media.mark-ready") {
            MediaTable.update(
                where = { (MediaTable.mediaId eq mediaId) and (MediaTable.state eq MediaState.PENDING.name) },
            ) {
                it[state] = MediaState.READY.name
            } > 0
        }

    override suspend fun attach(
        mediaId: UUID,
        posterId: UserId,
        momentId: MomentId,
        now: Instant,
        expiresAt: Instant,
    ): Boolean =
        database.tx("media.attach") {
            MediaTable.update(
                where = {
                    (MediaTable.mediaId eq mediaId) and
                        (MediaTable.posterId eq posterId.value) and
                        (MediaTable.state eq MediaState.READY.name) and
                        MediaTable.momentId.isNull() and
                        (MediaTable.expiresAt greater now)
                },
            ) {
                it[this.momentId] = momentId.value
                it[this.expiresAt] = expiresAt
            } > 0
        }

    override suspend fun expireFor(momentId: MomentId, now: Instant): List<MomentMedia> =
        database.tx("media.expire-for") {
            MediaTable
                .updateReturning(where = { MediaTable.momentId eq momentId.value }) {
                    it[expiresAt] = now
                }.map { it.toMedia() }
                .toList()
        }

    override suspend fun purgeable(now: Instant, limit: Int): List<MomentMedia> =
        database.tx("media.purgeable") {
            MediaTable
                .selectAll()
                .where { MediaTable.expiresAt lessEq now }
                .orderBy(MediaTable.expiresAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toMedia() }
                .toList()
        }

    override suspend fun delete(mediaId: UUID): Boolean =
        database.tx("media.delete") {
            MediaTable.deleteWhere { MediaTable.mediaId eq mediaId } > 0
        }

    override suspend fun allOf(posterId: UserId): List<MomentMedia> =
        database.tx("media.all-of") {
            MediaTable
                .selectAll()
                .where { MediaTable.posterId eq posterId.value }
                .map { it.toMedia() }
                .toList()
        }
}

private fun ResultRow.toMedia() =
    MomentMedia(
        mediaId = this[MediaTable.mediaId],
        posterId = UserId(this[MediaTable.posterId]),
        kind = MediaKind.valueOf(this[MediaTable.kind]),
        sizeBytes = this[MediaTable.sizeBytes],
        state = MediaState.valueOf(this[MediaTable.state]),
        momentId = this[MediaTable.momentId]?.let(::MomentId),
        createdAt = this[MediaTable.createdAt],
        expiresAt = this[MediaTable.expiresAt],
    )
