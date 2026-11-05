package com.circaso.spaces.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.spaces.api.MembershipUpdatedFrame
import com.circaso.spaces.api.PostId
import com.circaso.spaces.api.PostUpdatedFrame
import com.circaso.spaces.api.ReportNewFrame
import com.circaso.spaces.api.ReportResolvedFrame
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.errors.SpacesError
import com.circaso.spaces.domain.models.MuteDuration
import com.circaso.spaces.domain.models.OpsReportView
import com.circaso.spaces.domain.models.Page
import com.circaso.spaces.domain.models.RemovedBy
import com.circaso.spaces.domain.models.Report
import com.circaso.spaces.domain.models.ReportAction
import com.circaso.spaces.domain.models.ReportCommand
import com.circaso.spaces.domain.models.ReportGroupView
import com.circaso.spaces.domain.models.ReportState
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.domain.models.SpaceState
import com.circaso.spaces.domain.ports.FrameSender
import com.circaso.spaces.domain.ports.Transactor
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.PostRepository
import com.circaso.spaces.domain.repositories.ReportRepository
import com.circaso.spaces.domain.repositories.SpaceRepository
import com.circaso.spaces.domain.support.Gate
import com.circaso.spaces.domain.support.Lifecycle
import com.circaso.spaces.domain.support.PostViews
import com.circaso.spaces.domain.support.SpacesConfig
import com.circaso.spaces.domain.support.profileOf
import com.circaso.spaces.domain.support.removedFrame
import com.circaso.spaces.domain.support.toFrame
import com.circaso.users.api.Profiles
import java.time.Clock
import java.time.Duration

/**
 * SP35 (SR3): report a post (to the space's moderators and Circaso) or the whole space (to Circaso only). One open
 * report per person and target; at most 20 a day. The reporter is never named to the space.
 */
class ReportContent(
    private val members: MemberRepository,
    private val posts: PostRepository,
    private val reports: ReportRepository,
    private val gate: Gate,
    private val frames: FrameSender,
    private val config: SpacesConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: ReportCommand): Resource<Report> {
        val detail = command.detail?.trim()?.takeIf { it.isNotEmpty() }
        if (detail != null && detail.length > config.reportDetailMax) return Resource.error(SpacesError.ReportInvalid)
        when (val seat = gate.seat(command.spaceId, command.reporter)) {
            is Resource.Error -> return seat
            is Resource.Success -> Unit
        }
        command.postId?.let { id ->
            posts.find(id)?.takeIf { it.spaceId == command.spaceId } ?: return Resource.error(SpacesError.PostNotFound)
        }
        val now = clock.instant()
        val (today, oldest) = reports.countSince(command.reporter, now.minus(DAY))
        if (today >= config.reportsPerDay) {
            val wait = Duration.between(now, (oldest ?: now).plus(DAY)).seconds.coerceAtLeast(1)
            return Resource.error(SpacesError.TooManyReports(wait))
        }
        val fresh =
            Report(
                reportId = Uuid7.generate(),
                spaceId = command.spaceId,
                postId = command.postId,
                reporterId = command.reporter,
                reason = command.reason,
                detail = detail,
                state = ReportState.OPEN,
                createdAt = now,
                resolvedAt = null,
                resolvedBy = null,
                action = null,
            )
        val kept = reports.insertOrOpen(fresh)
        val postId = command.postId
        if (kept.reportId == fresh.reportId && postId != null) {
            val count = reports.ofSpace(command.spaceId, open = true).count { it.postId == postId }
            val team = members.team(command.spaceId).map { it.userId }
            frames.send(team, ReportNewFrame(command.spaceId, postId, count))
        }
        return Resource.success(kept)
    }

    private companion object {
        val DAY: Duration = Duration.ofDays(1)
    }
}

/** SP36 (SR4): moderators see the space's post reports, one entry per post; the reporters stay unnamed. */
class ListReports(
    private val posts: PostRepository,
    private val reports: ReportRepository,
    private val gate: Gate,
    private val views: PostViews,
    private val profiles: Profiles,
) {
    suspend operator fun invoke(caller: UserId, spaceId: SpaceId, open: Boolean): Resource<List<ReportGroupView>> {
        when (val seat = gate.require(spaceId, caller, Role.MODERATOR, SpacesError.NotModerator)) {
            is Resource.Error -> return seat
            is Resource.Success -> Unit
        }
        val groups = reports.ofSpace(spaceId, open).filter { it.postId != null }.groupBy { checkNotNull(it.postId) }
        val found = posts.findAll(groups.keys).associateBy { it.postId }
        val postViews = views.of(spaceId, found.values.toList(), caller).associateBy { it.post.postId }
        val resolvers = profiles.get(groups.values.flatten().mapNotNull { it.resolvedBy }.toSet())
        val items =
            groups.mapNotNull { (postId, list) ->
                val view = postViews[postId] ?: return@mapNotNull null
                val reason = list.groupingBy { it.reason }.eachCount().maxByOrNull { it.value }!!.key
                val resolved = list.firstOrNull { it.resolvedAt != null }
                ReportGroupView(
                    post = view,
                    reason = reason,
                    count = list.size,
                    firstAt = list.minOf { it.createdAt },
                    lastAt = list.maxOf { it.createdAt },
                    state = if (open) ReportState.OPEN else ReportState.DONE,
                    action = resolved?.action,
                    resolvedBy = resolved?.resolvedBy?.let { resolvers.profileOf(it) },
                    resolvedAt = resolved?.resolvedAt,
                )
            }
        val ordered = if (open) items.sortedByDescending { it.count } else items.sortedByDescending { it.resolvedAt }
        return Resource.success(ordered)
    }
}

/**
 * SP37: a moderator acts on a reported post (keep it, remove it, mute its author, or, for admins, remove its author)
 * and every open report on it closes; each reporter hears `space.report_resolved`.
 */
class ResolveReport(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val posts: PostRepository,
    private val reports: ReportRepository,
    private val transactor: Transactor,
    private val gate: Gate,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: UserId,
        spaceId: SpaceId,
        postId: PostId,
        action: ReportAction,
        muteDuration: MuteDuration?,
    ): Resource<Unit> {
        val seat =
            when (val found = gate.require(spaceId, caller, Role.MODERATOR, SpacesError.NotModerator)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        if (action == ReportAction.REMOVE_MEMBER && !seat.isAtLeast(Role.ADMIN)) return Resource.error(SpacesError.NotAdmin)
        if (reports.ofSpace(spaceId, open = true).none { it.postId == postId }) return Resource.error(SpacesError.ReportNotFound)
        val post = posts.find(postId)?.takeIf { it.spaceId == spaceId } ?: return Resource.error(SpacesError.ReportNotFound)
        val author = members.find(spaceId, post.authorId)?.takeIf { it.isActive }
        val now = clock.instant()
        val everyone = members.active(spaceId).map { it.userId }
        when (action) {
            ReportAction.KEEP -> Unit

            ReportAction.REMOVE_POST -> {
                posts.remove(postId, RemovedBy.MODERATOR, now)?.let { removed ->
                    val counts = posts.reactionCounts(listOf(postId))[postId].orEmpty()
                    frames.send(everyone, PostUpdatedFrame(spaceId, postId, counts.toFrame(), removedFrame(removed)))
                }
            }

            ReportAction.MUTE -> {
                if (author != null) {
                    if (!seat.outranks(author)) return Resource.error(SpacesError.CannotModerate)
                    val until =
                        when (muteDuration ?: MuteDuration.DAY) {
                            MuteDuration.HOUR -> now.plus(Duration.ofHours(1))
                            MuteDuration.DAY -> now.plus(Duration.ofDays(1))
                            MuteDuration.WEEK -> now.plus(Duration.ofDays(7))
                            MuteDuration.UNTIL_UNMUTED -> FOREVER
                        }
                    members.save(author.copy(mutedUntil = until))
                    frames.send(listOf(author.userId), MembershipUpdatedFrame(spaceId, seat.space.name, "MUTED", until.toString()))
                }
            }

            ReportAction.REMOVE_MEMBER -> {
                if (author != null) {
                    if (!seat.outranks(author)) return Resource.error(SpacesError.CannotModerate)
                    transactor.run("members.remove-reported") {
                        members.delete(spaceId, author.userId)
                        spaces.refreshMemberCount(spaceId)
                    }
                    frames.send(listOf(author.userId), MembershipUpdatedFrame(spaceId, seat.space.name, "REMOVED"))
                }
            }
        }
        val closed = reports.resolve(spaceId, postId, caller, action, now)
        closed.forEach {
            frames.send(listOf(it.reporterId), ReportResolvedFrame(spaceId, seat.space.name, it.reportId.toString(), action.name))
        }
        return Resource.success(Unit)
    }
}

/** SPO1: Circaso's review queue, newest first, with the reporter (Circaso sees who reported). */
class ListOpsReports(
    private val spaces: SpaceRepository,
    private val posts: PostRepository,
    private val reports: ReportRepository,
) {
    suspend operator fun invoke(offset: Int, limit: Int): Resource<Page<OpsReportView>> {
        val list = reports.all(offset, limit + 1)
        val page = list.take(limit)
        val names = spaces.findAll(page.map { it.spaceId }.toSet()).associate { it.spaceId to it.name }
        val texts = posts.findAll(page.mapNotNull { it.postId }.toSet()).associate { it.postId to it.text }
        val items = page.map { OpsReportView(it, names[it.spaceId].orEmpty(), it.postId?.let { id -> texts[id] }) }
        return Resource.success(Page(items, (offset + limit).takeIf { list.size > limit }?.toString()))
    }
}

/** SPO2: Circaso removes a space for breaking the rules (SS6d); everyone in it hears `space.updated`. */
class RemoveSpace(
    private val spaces: SpaceRepository,
    private val lifecycle: Lifecycle,
    private val clock: Clock,
) {
    suspend operator fun invoke(spaceId: SpaceId, reason: String): Resource<Unit> {
        repeat(RETRIES) {
            val space = spaces.find(spaceId) ?: return Resource.error(SpacesError.SpaceNotFound)
            if (space.state == SpaceState.REMOVED) return Resource.success(Unit)
            val removed =
                spaces.update(space, space.copy(state = SpaceState.REMOVED, removedReason = reason.take(REASON_MAX), updatedAt = clock.instant()))
            if (removed != null) {
                lifecycle.announce(removed)
                return Resource.success(Unit)
            }
        }
        return Resource.error(SpacesError.SpaceNotFound)
    }

    private companion object {
        const val RETRIES = 3
        const val REASON_MAX = 500
    }
}
