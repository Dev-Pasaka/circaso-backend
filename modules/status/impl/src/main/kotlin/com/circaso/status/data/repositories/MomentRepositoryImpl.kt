package com.circaso.status.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import com.circaso.status.data.tables.AudienceTable
import com.circaso.status.data.tables.MomentsTable
import com.circaso.status.domain.models.AudienceMember
import com.circaso.status.domain.models.Moment
import com.circaso.status.domain.models.ViewRecord
import com.circaso.status.domain.repositories.MomentRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant
import java.util.UUID

/** ST3-ST7 and account removal over `moments` and `audience`. Every read of a live moment filters `expires_at`. */
class MomentRepositoryImpl(private val database: Database) : MomentRepository {
    override suspend fun insert(moment: Moment, audience: List<AudienceMember>): Boolean =
        database.tx("moments.insert") {
            val claimed =
                MomentsTable.insertIgnore {
                    it[momentId] = moment.momentId.value
                    it[posterId] = moment.posterId.value
                    it[clientMomentId] = moment.clientMomentId
                    it[ciphertext] = moment.ciphertext
                    it[mediaId] = moment.mediaId
                    it[postedAt] = moment.postedAt
                    it[expiresAt] = moment.expiresAt
                }
            if (claimed.insertedCount == 0) return@tx false
            for (chunk in audience.chunked(AUDIENCE_CHUNK)) {
                AudienceTable.batchInsert(chunk, useMultiRowValues = true, shouldReturnGeneratedValues = false) {
                    this[AudienceTable.momentId] = moment.momentId.value
                    this[AudienceTable.userId] = it.userId.value
                    this[AudienceTable.keyCopy] = it.keyCopy
                }
            }
            true
        }

    override suspend fun findByClientId(posterId: UserId, clientMomentId: UUID): Moment? =
        database.tx("moments.find-by-client-id") {
            MomentsTable
                .selectAll()
                .where {
                    (MomentsTable.posterId eq posterId.value) and (MomentsTable.clientMomentId eq clientMomentId)
                }.map { it.toMoment() }
                .singleOrNull()
        }

    override suspend fun findLive(momentId: MomentId, now: Instant): Moment? =
        database.tx("moments.find") {
            MomentsTable
                .selectAll()
                .where { (MomentsTable.momentId eq momentId.value) and (MomentsTable.expiresAt greater now) }
                .map { it.toMoment() }
                .singleOrNull()
        }

    override suspend fun member(momentId: MomentId, userId: UserId): AudienceMember? =
        database.tx("audience.find") {
            AudienceTable
                .selectAll()
                .where { (AudienceTable.momentId eq momentId.value) and (AudienceTable.userId eq userId.value) }
                .map { it.toMember() }
                .singleOrNull()
        }

    override suspend fun audienceOf(momentId: MomentId): List<UserId> =
        database.tx("audience.of") {
            AudienceTable
                .select(AudienceTable.userId)
                .where { AudienceTable.momentId eq momentId.value }
                .map { UserId(it[AudienceTable.userId]) }
                .toList()
        }

    override suspend fun postedSince(posterId: UserId, since: Instant): Pair<Int, Instant?> =
        database.tx("moments.posted-since") {
            val times =
                MomentsTable
                    .select(MomentsTable.postedAt)
                    .where { (MomentsTable.posterId eq posterId.value) and (MomentsTable.postedAt greater since) }
                    .map { it[MomentsTable.postedAt] }
                    .toList()
            times.size to times.minOrNull()
        }

    override suspend fun visibleTo(
        viewer: UserId,
        after: MomentId?,
        now: Instant,
        limit: Int,
    ): List<Pair<Moment, AudienceMember>> =
        database.tx("moments.visible-to") {
            val shownToViewer = (AudienceTable.userId eq viewer.value) and (MomentsTable.expiresAt greater now)
            AudienceTable
                .join(MomentsTable, JoinType.INNER, AudienceTable.momentId, MomentsTable.momentId)
                .selectAll()
                .where { shownToViewer and afterId(AudienceTable.momentId, after) }
                .orderBy(AudienceTable.momentId to SortOrder.ASC)
                .limit(limit)
                .map { it.toMoment() to it.toMember() }
                .toList()
        }

    override suspend fun postedBy(
        posterId: UserId,
        after: MomentId?,
        now: Instant,
        limit: Int,
    ): List<Moment> =
        database.tx("moments.posted-by") {
            MomentsTable
                .selectAll()
                .where {
                    (MomentsTable.posterId eq posterId.value) and (MomentsTable.expiresAt greater now) and
                        afterId(MomentsTable.momentId, after)
                }.orderBy(MomentsTable.momentId to SortOrder.ASC)
                .limit(limit)
                .map { it.toMoment() }
                .toList()
        }

    override suspend fun viewCounts(momentIds: Collection<MomentId>): Map<MomentId, Int> {
        if (momentIds.isEmpty()) return emptyMap()
        return database.tx("audience.view-counts") {
            val viewers = AudienceTable.userId.count()
            AudienceTable
                .select(AudienceTable.momentId, viewers)
                .where {
                    (AudienceTable.momentId inList momentIds.map { it.value }) and AudienceTable.viewedAt.isNotNull()
                }.groupBy(AudienceTable.momentId)
                .map { MomentId(it[AudienceTable.momentId]) to it[viewers].toInt() }
                .toList()
                .toMap()
        }
    }

    override suspend fun recordView(
        momentId: MomentId,
        viewer: UserId,
        at: Instant,
        reacted: Boolean?,
    ): AudienceMember? =
        database.tx("audience.record-view") {
            val row = (AudienceTable.momentId eq momentId.value) and (AudienceTable.userId eq viewer.value)
            AudienceTable.update(where = { row and AudienceTable.viewedAt.isNull() }) { it[viewedAt] = at }
            if (reacted != null) AudienceTable.update(where = { row }) { it[this.reacted] = reacted }
            AudienceTable
                .selectAll()
                .where { row }
                .map { it.toMember() }
                .singleOrNull()
        }

    override suspend fun views(momentId: MomentId): List<ViewRecord> =
        database.tx("audience.views") {
            AudienceTable
                .selectAll()
                .where { (AudienceTable.momentId eq momentId.value) and AudienceTable.viewedAt.isNotNull() }
                .orderBy(AudienceTable.viewedAt to SortOrder.ASC)
                .map {
                    ViewRecord(
                        UserId(it[AudienceTable.userId]),
                        it[AudienceTable.viewedAt]!!,
                        it[AudienceTable.reacted],
                    )
                }.toList()
        }

    override suspend fun delete(momentId: MomentId): Boolean =
        database.tx("moments.delete") {
            MomentsTable.deleteWhere { MomentsTable.momentId eq momentId.value } > 0
        }

    override suspend fun allPostedBy(posterId: UserId): List<Moment> =
        database.tx("moments.all-posted-by") {
            MomentsTable
                .selectAll()
                .where { MomentsTable.posterId eq posterId.value }
                .map { it.toMoment() }
                .toList()
        }

    override suspend fun leaveAudiences(userId: UserId): Int =
        database.tx("audience.leave-all") {
            AudienceTable.deleteWhere { AudienceTable.userId eq userId.value }
        }

    private companion object {
        /** Audience rows per multi-row `INSERT`, well under PostgreSQL's bind limit. */
        const val AUDIENCE_CHUNK = 500
    }
}

private fun afterId(column: Column<UUID>, after: MomentId?): Op<Boolean> =
    if (after == null) Op.TRUE else column greater after.value

private fun ResultRow.toMoment() =
    Moment(
        momentId = MomentId(this[MomentsTable.momentId]),
        posterId = UserId(this[MomentsTable.posterId]),
        clientMomentId = this[MomentsTable.clientMomentId],
        ciphertext = this[MomentsTable.ciphertext],
        mediaId = this[MomentsTable.mediaId],
        postedAt = this[MomentsTable.postedAt],
        expiresAt = this[MomentsTable.expiresAt],
    )

private fun ResultRow.toMember() =
    AudienceMember(
        userId = UserId(this[AudienceTable.userId]),
        keyCopy = this[AudienceTable.keyCopy],
        viewedAt = this[AudienceTable.viewedAt],
        reacted = this[AudienceTable.reacted],
    )
