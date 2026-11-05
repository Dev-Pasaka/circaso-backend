package com.circaso.spaces.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.shared.UserId
import com.circaso.spaces.api.PostId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.data.tables.MediaTable
import com.circaso.spaces.data.tables.PostsTable
import com.circaso.spaces.data.tables.ReactionsTable
import com.circaso.spaces.data.tables.ReportsTable
import com.circaso.spaces.domain.models.MediaKind
import com.circaso.spaces.domain.models.MediaPurpose
import com.circaso.spaces.domain.models.MediaState
import com.circaso.spaces.domain.models.Post
import com.circaso.spaces.domain.models.ReactionCount
import com.circaso.spaces.domain.models.RemovedBy
import com.circaso.spaces.domain.models.Report
import com.circaso.spaces.domain.models.ReportAction
import com.circaso.spaces.domain.models.ReportReason
import com.circaso.spaces.domain.models.ReportState
import com.circaso.spaces.domain.models.SpaceMedia
import com.circaso.spaces.domain.repositories.MediaRepository
import com.circaso.spaces.domain.repositories.PostRepository
import com.circaso.spaces.domain.repositories.ReportRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.greaterEq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.time.Instant
import java.util.UUID

/** `spaces.posts` and `spaces.reactions`. A post's files are kept as a comma-separated list of ids, in order. */
class PostRepositoryImpl(private val database: Database) : PostRepository {
    override suspend fun insert(post: Post): Boolean =
        database.tx("posts.insert") {
            PostsTable
                .insertIgnore {
                    it[postId] = post.postId.value
                    it[spaceId] = post.spaceId.value
                    it[authorId] = post.authorId.value
                    it[clientPostId] = post.clientPostId
                    it[text] = post.text
                    it[replyTo] = post.replyTo?.value
                    it[mediaIds] = post.mediaIds.joinToString(",")
                    it[createdAt] = post.createdAt
                }.insertedCount > 0
        }

    override suspend fun byClientId(authorId: UserId, clientPostId: UUID): Post? =
        database.tx("posts.by-client-id") {
            PostsTable
                .selectAll()
                .where { (PostsTable.authorId eq authorId.value) and (PostsTable.clientPostId eq clientPostId) }
                .map { it.toPost() }
                .singleOrNull()
        }

    override suspend fun find(postId: PostId): Post? =
        database.tx("posts.find") {
            PostsTable
                .selectAll()
                .where { PostsTable.postId eq postId.value }
                .map { it.toPost() }
                .singleOrNull()
        }

    override suspend fun findAll(postIds: Collection<PostId>): List<Post> {
        if (postIds.isEmpty()) return emptyList()
        return database.tx("posts.find-all") {
            PostsTable
                .selectAll()
                .where { PostsTable.postId inList postIds.map { it.value }.distinct() }
                .map { it.toPost() }
                .toList()
        }
    }

    override suspend fun before(spaceId: SpaceId, before: PostId?, limit: Int): List<Post> =
        database.tx("posts.before") {
            PostsTable
                .selectAll()
                .where {
                    var condition: Op<Boolean> = PostsTable.spaceId eq spaceId.value
                    if (before != null) condition = condition and (PostsTable.postId less before.value)
                    condition
                }.orderBy(PostsTable.postId to SortOrder.DESC)
                .limit(limit)
                .map { it.toPost() }
                .toList()
        }

    override suspend fun after(spaceId: SpaceId, after: PostId, limit: Int): List<Post> =
        database.tx("posts.after") {
            PostsTable
                .selectAll()
                .where { (PostsTable.spaceId eq spaceId.value) and (PostsTable.postId greater after.value) }
                .orderBy(PostsTable.postId to SortOrder.ASC)
                .limit(limit)
                .map { it.toPost() }
                .toList()
        }

    override suspend fun latest(spaceId: SpaceId): Post? = before(spaceId, null, 1).firstOrNull()

    override suspend fun countSince(authorId: UserId, since: Instant): Pair<Int, Instant?> =
        database.tx("posts.count-since") {
            val times =
                PostsTable
                    .select(PostsTable.createdAt)
                    .where { (PostsTable.authorId eq authorId.value) and (PostsTable.createdAt greater since) }
                    .map { it[PostsTable.createdAt] }
                    .toList()
            times.size to times.minOrNull()
        }

    override suspend fun remove(postId: PostId, by: RemovedBy, at: Instant): Post? =
        database.tx("posts.remove") {
            PostsTable.update(where = { (PostsTable.postId eq postId.value) and PostsTable.removedAt.isNull() }) {
                it[removedAt] = at
                it[removedBy] = by.name
                it[text] = null
                it[mediaIds] = ""
            }
            PostsTable
                .selectAll()
                .where { PostsTable.postId eq postId.value }
                .map { it.toPost() }
                .singleOrNull()
        }

    override suspend fun countAfter(spaceId: SpaceId, after: UUID?, cap: Int): Int =
        database.tx("posts.count-after") {
            PostsTable
                .select(PostsTable.postId)
                .where {
                    var condition: Op<Boolean> = (PostsTable.spaceId eq spaceId.value) and PostsTable.removedAt.isNull()
                    if (after != null) condition = condition and (PostsTable.postId greater after)
                    condition
                }.limit(cap)
                .map { it[PostsTable.postId] }
                .toList()
                .size
        }

    override suspend fun react(postId: PostId, userId: UserId, emoji: String?, at: Instant) {
        database.tx("reactions.set") {
            if (emoji == null) {
                ReactionsTable.deleteWhere { (ReactionsTable.postId eq postId.value) and (ReactionsTable.userId eq userId.value) }
            } else {
                ReactionsTable.upsert(ReactionsTable.postId, ReactionsTable.userId) {
                    it[this.postId] = postId.value
                    it[this.userId] = userId.value
                    it[this.emoji] = emoji
                    it[reactedAt] = at
                }
            }
        }
    }

    override suspend fun reactionCounts(postIds: Collection<PostId>): Map<PostId, List<ReactionCount>> {
        if (postIds.isEmpty()) return emptyMap()
        return database.tx("reactions.counts") {
            val total = ReactionsTable.userId.count()
            ReactionsTable
                .select(ReactionsTable.postId, ReactionsTable.emoji, total)
                .where { ReactionsTable.postId inList postIds.map { it.value }.distinct() }
                .groupBy(ReactionsTable.postId, ReactionsTable.emoji)
                .map { Triple(PostId(it[ReactionsTable.postId]), it[ReactionsTable.emoji], it[total].toInt()) }
                .toList()
                .groupBy({ it.first }) { ReactionCount(it.second, it.third) }
                .mapValues { (_, counts) -> counts.sortedWith(compareByDescending<ReactionCount> { it.count }.thenBy { it.emoji }) }
        }
    }

    override suspend fun myReactions(postIds: Collection<PostId>, userId: UserId): Map<PostId, String> {
        if (postIds.isEmpty()) return emptyMap()
        return database.tx("reactions.mine") {
            ReactionsTable
                .selectAll()
                .where { (ReactionsTable.postId inList postIds.map { it.value }.distinct()) and (ReactionsTable.userId eq userId.value) }
                .map { PostId(it[ReactionsTable.postId]) to it[ReactionsTable.emoji] }
                .toList()
                .toMap()
        }
    }

    private fun ResultRow.toPost() =
        Post(
            postId = PostId(this[PostsTable.postId]),
            spaceId = SpaceId(this[PostsTable.spaceId]),
            authorId = UserId(this[PostsTable.authorId]),
            clientPostId = this[PostsTable.clientPostId],
            text = this[PostsTable.text],
            replyTo = this[PostsTable.replyTo]?.let(::PostId),
            mediaIds = this[PostsTable.mediaIds].split(',').filter { it.isNotBlank() }.map(UUID::fromString),
            createdAt = this[PostsTable.createdAt],
            removedAt = this[PostsTable.removedAt],
            removedBy = this[PostsTable.removedBy]?.let(RemovedBy::valueOf),
        )
}

class MediaRepositoryImpl(private val database: Database) : MediaRepository {
    override suspend fun insert(media: SpaceMedia) {
        database.tx("media.insert") {
            MediaTable.insert {
                it[mediaId] = media.mediaId
                it[ownerId] = media.ownerId.value
                it[purpose] = media.purpose.name
                it[kind] = media.kind.name
                it[contentType] = media.contentType
                it[sizeBytes] = media.sizeBytes
                it[width] = media.width
                it[height] = media.height
                it[durationMs] = media.durationMs
                it[state] = media.state.name
                it[createdAt] = media.createdAt
                it[expiresAt] = media.expiresAt
            }
        }
    }

    override suspend fun find(mediaId: UUID): SpaceMedia? =
        database.tx("media.find") {
            MediaTable
                .selectAll()
                .where { MediaTable.mediaId eq mediaId }
                .map { it.toMedia() }
                .singleOrNull()
        }

    override suspend fun findAll(mediaIds: Collection<UUID>): List<SpaceMedia> {
        if (mediaIds.isEmpty()) return emptyList()
        return database.tx("media.find-all") {
            MediaTable
                .selectAll()
                .where { MediaTable.mediaId inList mediaIds.distinct() }
                .map { it.toMedia() }
                .toList()
        }
    }

    override suspend fun markReady(mediaId: UUID, sizeBytes: Long): Boolean =
        database.tx("media.ready") {
            MediaTable.update(where = { (MediaTable.mediaId eq mediaId) and (MediaTable.state eq MediaState.PENDING.name) }) {
                it[state] = MediaState.READY.name
                it[this.sizeBytes] = sizeBytes
            } > 0
        }

    override suspend fun use(mediaIds: Collection<UUID>, ownerId: UserId): Boolean {
        val ids = mediaIds.distinct()
        if (ids.isEmpty()) return true
        return database.tx("media.use") {
            val changed =
                MediaTable.update(where = {
                    (MediaTable.mediaId inList ids) and (MediaTable.ownerId eq ownerId.value) and
                        (MediaTable.state eq MediaState.READY.name)
                }) {
                    it[state] = MediaState.USED.name
                    it[expiresAt] = KEPT
                }
            changed == ids.size
        }
    }

    override suspend fun expired(now: Instant, limit: Int): List<SpaceMedia> =
        database.tx("media.expired") {
            MediaTable
                .selectAll()
                .where { (MediaTable.state neq MediaState.USED.name) and (MediaTable.expiresAt lessEq now) }
                .limit(limit)
                .map { it.toMedia() }
                .toList()
        }

    override suspend fun delete(mediaId: UUID) {
        database.tx("media.delete") { MediaTable.deleteWhere { MediaTable.mediaId eq mediaId } }
    }

    private fun ResultRow.toMedia() =
        SpaceMedia(
            mediaId = this[MediaTable.mediaId],
            ownerId = UserId(this[MediaTable.ownerId]),
            purpose = MediaPurpose.valueOf(this[MediaTable.purpose]),
            kind = MediaKind.valueOf(this[MediaTable.kind]),
            contentType = this[MediaTable.contentType],
            sizeBytes = this[MediaTable.sizeBytes],
            width = this[MediaTable.width],
            height = this[MediaTable.height],
            durationMs = this[MediaTable.durationMs],
            state = MediaState.valueOf(this[MediaTable.state]),
            createdAt = this[MediaTable.createdAt],
            expiresAt = this[MediaTable.expiresAt],
        )

    private companion object {
        /** A used file is kept for as long as what uses it. */
        val KEPT: Instant = Instant.parse("9999-12-31T00:00:00Z")
    }
}

class ReportRepositoryImpl(private val database: Database) : ReportRepository {
    override suspend fun insertOrOpen(report: Report): Report =
        database.tx("reports.insert") {
            ReportsTable.insertIgnore {
                it[reportId] = report.reportId
                it[spaceId] = report.spaceId.value
                it[postId] = report.postId?.value
                it[reporterId] = report.reporterId.value
                it[reason] = report.reason.name
                it[detail] = report.detail
                it[state] = ReportState.OPEN.name
                it[createdAt] = report.createdAt
            }
            ReportsTable
                .selectAll()
                .where {
                    var condition: Op<Boolean> =
                        (ReportsTable.reporterId eq report.reporterId.value) and (ReportsTable.spaceId eq report.spaceId.value) and
                            (ReportsTable.state eq ReportState.OPEN.name)
                    condition =
                        if (report.postId == null) condition and ReportsTable.postId.isNull() else condition and (ReportsTable.postId eq report.postId.value)
                    condition
                }.map { it.toReport() }
                .singleOrNull() ?: report
        }

    override suspend fun countSince(reporterId: UserId, since: Instant): Pair<Int, Instant?> =
        database.tx("reports.count-since") {
            val times =
                ReportsTable
                    .select(ReportsTable.createdAt)
                    .where { (ReportsTable.reporterId eq reporterId.value) and (ReportsTable.createdAt greaterEq since) }
                    .map { it[ReportsTable.createdAt] }
                    .toList()
            times.size to times.minOrNull()
        }

    override suspend fun ofSpace(spaceId: SpaceId, open: Boolean): List<Report> =
        database.tx("reports.of-space") {
            val state = if (open) ReportState.OPEN else ReportState.DONE
            ReportsTable
                .selectAll()
                .where { (ReportsTable.spaceId eq spaceId.value) and (ReportsTable.state eq state.name) and ReportsTable.postId.isNotNull() }
                .orderBy(ReportsTable.createdAt to SortOrder.DESC)
                .limit(REPORTS_CAP)
                .map { it.toReport() }
                .toList()
        }

    override suspend fun resolve(
        spaceId: SpaceId,
        postId: PostId,
        by: UserId,
        action: ReportAction,
        at: Instant,
    ): List<Report> =
        database.tx("reports.resolve") {
            val open =
                ReportsTable
                    .selectAll()
                    .where {
                        (ReportsTable.spaceId eq spaceId.value) and (ReportsTable.postId eq postId.value) and
                            (ReportsTable.state eq ReportState.OPEN.name)
                    }.map { it.toReport() }
                    .toList()
            if (open.isEmpty()) return@tx emptyList()
            ReportsTable.update(where = { ReportsTable.reportId inList open.map { it.reportId } }) {
                it[state] = ReportState.DONE.name
                it[resolvedAt] = at
                it[resolvedBy] = by.value
                it[this.action] = action.name
            }
            open
        }

    override suspend fun all(offset: Int, limit: Int): List<Report> =
        database.tx("reports.all") {
            ReportsTable
                .selectAll()
                .orderBy(ReportsTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .offset(offset.toLong())
                .map { it.toReport() }
                .toList()
        }

    private fun ResultRow.toReport() =
        Report(
            reportId = this[ReportsTable.reportId],
            spaceId = SpaceId(this[ReportsTable.spaceId]),
            postId = this[ReportsTable.postId]?.let(::PostId),
            reporterId = UserId(this[ReportsTable.reporterId]),
            reason = ReportReason.valueOf(this[ReportsTable.reason]),
            detail = this[ReportsTable.detail],
            state = ReportState.valueOf(this[ReportsTable.state]),
            createdAt = this[ReportsTable.createdAt],
            resolvedAt = this[ReportsTable.resolvedAt],
            resolvedBy = this[ReportsTable.resolvedBy]?.let(::UserId),
            action = this[ReportsTable.action]?.let(ReportAction::valueOf),
        )

    private companion object {
        const val REPORTS_CAP = 1_000
    }
}
