package com.circaso.spaces.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import com.circaso.spaces.api.PostId
import com.circaso.spaces.api.PostNewFrame
import com.circaso.spaces.api.PostUpdatedFrame
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.errors.SpacesError
import com.circaso.spaces.domain.models.CreatePostCommand
import com.circaso.spaces.domain.models.MediaPurpose
import com.circaso.spaces.domain.models.MediaState
import com.circaso.spaces.domain.models.Membership
import com.circaso.spaces.domain.models.NotificationLevel
import com.circaso.spaces.domain.models.Page
import com.circaso.spaces.domain.models.Post
import com.circaso.spaces.domain.models.PostView
import com.circaso.spaces.domain.models.PostingMode
import com.circaso.spaces.domain.models.PostsQuery
import com.circaso.spaces.domain.models.ReactionResult
import com.circaso.spaces.domain.models.RemovedBy
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.domain.ports.FrameSender
import com.circaso.spaces.domain.ports.Transactor
import com.circaso.spaces.domain.repositories.MediaRepository
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.PostRepository
import com.circaso.spaces.domain.repositories.SpaceRepository
import com.circaso.spaces.domain.support.Gate
import com.circaso.spaces.domain.support.PostViews
import com.circaso.spaces.domain.support.SpacesConfig
import com.circaso.spaces.domain.support.removedFrame
import com.circaso.spaces.domain.support.toFrame
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** One emoji: 1 to 16 UTF-8 bytes with no letters, digits or spaces (the Moments rule). */
internal fun isEmoji(text: String): Boolean {
    val bytes = text.toByteArray(Charsets.UTF_8).size
    if (bytes !in 1..EMOJI_MAX_BYTES) return false
    return text.codePoints().noneMatch { Character.isLetterOrDigit(it) || Character.isWhitespace(it) }
}

private const val EMOJI_MAX_BYTES = 16

/**
 * SP28: members read the whole chat; visitors of a public space read its latest posts only (SS1); members-only and
 * invite-only visitors, ended memberships and banned people read nothing.
 */
class ListPosts(
    private val posts: PostRepository,
    private val gate: Gate,
    private val views: PostViews,
    private val config: SpacesConfig,
) {
    suspend operator fun invoke(query: PostsQuery): Resource<Page<PostView>> {
        val seat =
            when (val found = gate.seat(query.spaceId, query.caller)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        if (seat.banned) return Resource.error(SpacesError.Banned)
        if (!seat.isMember) {
            if (seat.membership != null) return Resource.error(SpacesError.MembershipEnded)
            if (!Gate.visitorsRead(seat.space) || !seat.space.isLive) return Resource.error(SpacesError.MembersOnly)
            val latest = posts.before(query.spaceId, null, config.visitorPosts.coerceAtMost(query.limit))
            return Resource.success(Page(views.of(query.spaceId, latest, query.caller), next = null))
        }
        val list =
            if (query.after != null) {
                posts.after(query.spaceId, query.after, query.limit + 1)
            } else {
                posts.before(query.spaceId, query.before, query.limit + 1)
            }
        val page = list.take(query.limit)
        val next =
            page
                .lastOrNull()
                ?.postId
                ?.toString()
                ?.takeIf { list.size > query.limit }
        return Resource.success(Page(views.of(query.spaceId, page, query.caller), next))
    }
}

/**
 * SP29: a post by a member of a live space, queueable on the phone (`clientPostId` makes a repeat return the first).
 * Admins-only spaces take posts from the owner and admins; a muted member can't post until the mute ends. Everyone
 * in the space gets `space.post_new`; those whose notifications want it are marked to be pushed.
 */
class CreatePost(
    private val spaces: SpaceRepository,
    private val members: MemberRepository,
    private val posts: PostRepository,
    private val media: MediaRepository,
    private val transactor: Transactor,
    private val gate: Gate,
    private val views: PostViews,
    private val frames: FrameSender,
    private val config: SpacesConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: CreatePostCommand): Resource<PostView> {
        val text = command.text?.trim()?.takeIf { it.isNotEmpty() }
        invalid(text, command)?.let { return Resource.error(SpacesError.PostInvalid(it)) }
        posts.byClientId(command.caller, command.clientPostId)?.let {
            return Resource.success(views.one(command.spaceId, it, command.caller))
        }
        val seat =
            when (val found = gate.participant(command.spaceId, command.caller)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val space = seat.space
        val membership = checkNotNull(seat.membership)
        val now = clock.instant()
        if (space.postingMode == PostingMode.ADMINS_ONLY && !seat.isAtLeast(Role.ADMIN)) {
            return Resource.error(SpacesError.PostingRestricted)
        }
        if (membership.isMuted(
                now,
            )
        ) {
            return Resource.error(SpacesError.Muted(checkNotNull(membership.mutedUntil).toString()))
        }
        rateLimit(command.caller, now)?.let { return Resource.error(it) }
        val reply =
            command.replyTo?.let {
                posts.find(it)?.takeIf { p -> p.spaceId == command.spaceId }
                    ?: return Resource.error(SpacesError.PostNotFound)
            }
        if (command.mediaIds.isNotEmpty()) {
            val files = media.findAll(command.mediaIds)
            val usable =
                files.size == command.mediaIds.size &&
                    files.all {
                        it.ownerId == command.caller && it.purpose == MediaPurpose.POST &&
                            it.state == MediaState.READY
                    }
            if (!usable) return Resource.error(SpacesError.MediaNotFound)
        }
        val post =
            Post(
                postId = PostId(Uuid7.generate()),
                spaceId = command.spaceId,
                authorId = command.caller,
                clientPostId = command.clientPostId,
                text = text,
                replyTo = reply?.postId,
                mediaIds = command.mediaIds,
                createdAt = now,
                removedAt = null,
                removedBy = null,
            )
        val written =
            transactor.attempt("posts.create") {
                if (!posts.insert(post)) return@attempt Resource.success(false)
                if (command.mediaIds.isNotEmpty() && !media.use(command.mediaIds, command.caller)) {
                    return@attempt Resource.error(SpacesError.MediaNotFound)
                }
                spaces.touchActivity(command.spaceId, now)
                Resource.success(true)
            }
        when (written) {
            is Resource.Error -> {
                return written
            }

            is Resource.Success -> {
                if (!written.data) {
                    val first =
                        posts.byClientId(command.caller, command.clientPostId)
                            ?: return Resource.error(SpacesError.PostNotFound)
                    return Resource.success(views.one(command.spaceId, first, command.caller))
                }
            }
        }
        val view = views.one(command.spaceId, post, command.caller)
        announce(space.name, view, reply, members.active(command.spaceId), seat.isAtLeast(Role.MODERATOR))
        members.markRead(command.spaceId, command.caller, post.postId.value)
        return Resource.success(view)
    }

    private fun invalid(text: String?, command: CreatePostCommand): String? =
        when {
            text == null && command.mediaIds.isEmpty() -> "text or media is required"
            text != null && text.length > config.postTextMax -> "text is at most ${config.postTextMax} characters"
            command.mediaIds.size > config.mediaPerPost -> "at most ${config.mediaPerPost} files"
            command.mediaIds.size != command.mediaIds.toSet().size -> "a file appears twice"
            else -> null
        }

    private suspend fun rateLimit(author: UserId, now: Instant): SpacesError? {
        val (minute, oldestMinute) = posts.countSince(author, now.minus(MINUTE))
        if (minute >= config.postsPerMinute) return SpacesError.TooManyPosts(wait(oldestMinute, MINUTE, now))
        val (day, oldestDay) = posts.countSince(author, now.minus(DAY))
        if (day >= config.postsPerDay) return SpacesError.TooManyPosts(wait(oldestDay, DAY, now))
        return null
    }

    private fun wait(
        oldest: Instant?,
        window: Duration,
        now: Instant,
    ): Long = Duration.between(now, (oldest ?: now).plus(window)).seconds.coerceAtLeast(1)

    /** Two frames at most: one for the members it is pushed to, one for the rest. */
    private suspend fun announce(
        spaceName: String,
        view: PostView,
        reply: Post?,
        everyone: List<Membership>,
        byTeam: Boolean,
    ) {
        val author = view.post.authorId
        val (pushed, quiet) =
            everyone.partition {
                it.userId != author &&
                    when (it.notifications) {
                        NotificationLevel.ALL -> true
                        NotificationLevel.MENTIONS -> byTeam || reply?.authorId == it.userId
                        NotificationLevel.OFF -> false
                    }
            }
        val frame = view.toFrame()
        if (pushed.isNotEmpty()) {
            frames.send(
                pushed.map {
                    it.userId
                },
                PostNewFrame(view.post.spaceId, spaceName, frame, notify = true),
            )
        }
        if (quiet.isNotEmpty()) {
            frames.send(
                quiet.map {
                    it.userId
                },
                PostNewFrame(view.post.spaceId, spaceName, frame, notify = false),
            )
        }
    }

    private companion object {
        val MINUTE: Duration = Duration.ofMinutes(1)
        val DAY: Duration = Duration.ofDays(1)
    }
}

/** SP30: the author removes their own post, or a moderator any post: a tombstone stays, the moderator unnamed. */
class RemovePost(
    private val members: MemberRepository,
    private val posts: PostRepository,
    private val gate: Gate,
    private val views: PostViews,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: UserId,
        spaceId: SpaceId,
        postId: PostId,
    ): Resource<PostView> {
        val seat =
            when (val found = gate.seat(spaceId, caller)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        val post =
            posts.find(postId)?.takeIf { it.spaceId == spaceId } ?: return Resource.error(SpacesError.PostNotFound)
        val by =
            when {
                post.authorId == caller -> RemovedBy.AUTHOR
                seat.isAtLeast(Role.MODERATOR) -> RemovedBy.MODERATOR
                else -> return Resource.error(SpacesError.NotModerator)
            }
        val removed =
            if (post.isRemoved) {
                post
            } else {
                posts.remove(postId, by, clock.instant())
                    ?: return Resource.error(SpacesError.PostNotFound)
            }
        val view = views.one(spaceId, removed, caller)
        if (!post.isRemoved) {
            frames.send(
                members.active(spaceId).map {
                    it.userId
                },
                PostUpdatedFrame(spaceId, postId, view.reactions.toFrame(), removedFrame(removed)),
            )
        }
        return Resource.success(view)
    }
}

/** SP31: one emoji per person per post (any one emoji), or none; everyone in the space gets the new counts. */
class ReactToPost(
    private val members: MemberRepository,
    private val posts: PostRepository,
    private val gate: Gate,
    private val frames: FrameSender,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        caller: UserId,
        spaceId: SpaceId,
        postId: PostId,
        emoji: String?,
    ): Resource<ReactionResult> {
        if (emoji != null && !isEmoji(emoji)) return Resource.error(SpacesError.ReactionInvalid)
        when (val seat = gate.participant(spaceId, caller)) {
            is Resource.Error -> return seat
            is Resource.Success -> Unit
        }
        val post =
            posts.find(postId)?.takeIf { it.spaceId == spaceId && !it.isRemoved }
                ?: return Resource.error(SpacesError.PostNotFound)
        posts.react(post.postId, caller, emoji, clock.instant())
        val counts = posts.reactionCounts(listOf(postId))[postId].orEmpty()
        frames.send(members.active(spaceId).map { it.userId }, PostUpdatedFrame(spaceId, postId, counts.toFrame()))
        return Resource.success(ReactionResult(postId, counts, emoji))
    }
}

/** SP32: moves the caller's read marker forward; the unread count that's left. */
class MarkRead(
    private val members: MemberRepository,
    private val posts: PostRepository,
    private val gate: Gate,
    private val config: SpacesConfig,
) {
    suspend operator fun invoke(
        caller: UserId,
        spaceId: SpaceId,
        postId: PostId,
    ): Resource<Int> {
        val seat =
            when (val found = gate.seat(spaceId, caller)) {
                is Resource.Success -> found.data
                is Resource.Error -> return found
            }
        if (!seat.isMember) return Resource.error(SpacesError.NotMember)
        posts.find(postId)?.takeIf { it.spaceId == spaceId } ?: return Resource.error(SpacesError.PostNotFound)
        members.markRead(spaceId, caller, postId.value)
        val marker = members.find(spaceId, caller)?.readUpTo
        return Resource.success(posts.countAfter(spaceId, marker, config.unreadCap))
    }
}
