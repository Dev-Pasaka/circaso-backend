package com.circaso.spaces.domain.support

import com.circaso.shared.UserId
import com.circaso.spaces.api.FrameMedia
import com.circaso.spaces.api.FramePerson
import com.circaso.spaces.api.FramePost
import com.circaso.spaces.api.FrameReaction
import com.circaso.spaces.api.FrameRemoved
import com.circaso.spaces.api.FrameReplyTo
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.models.Post
import com.circaso.spaces.domain.models.PostMediaView
import com.circaso.spaces.domain.models.PostView
import com.circaso.spaces.domain.models.ReactionCount
import com.circaso.spaces.domain.models.ReplyView
import com.circaso.spaces.domain.ports.MediaStorage
import com.circaso.spaces.domain.repositories.MediaRepository
import com.circaso.spaces.domain.repositories.MemberRepository
import com.circaso.spaces.domain.repositories.PostRepository
import com.circaso.users.api.Profiles

/** Posts as SP28 shows them: author with today's role, files with fresh URLs, the quoted reply, reactions. */
class PostViews(
    private val posts: PostRepository,
    private val members: MemberRepository,
    private val media: MediaRepository,
    private val profiles: Profiles,
    private val storage: MediaStorage,
    private val config: SpacesConfig,
) {
    suspend fun of(spaceId: SpaceId, list: List<Post>, caller: UserId?): List<PostView> {
        if (list.isEmpty()) return emptyList()
        val replies = posts.findAll(list.mapNotNull { it.replyTo }.distinct()).associateBy { it.postId }
        val people = profiles.get((list.map { it.authorId } + replies.values.map { it.authorId }).toSet())
        val roles = members.among(spaceId, list.map { it.authorId }.distinct()).associate { it.userId to it.role }
        val files = media.findAll(list.filterNot { it.isRemoved }.flatMap { it.mediaIds }).associateBy { it.mediaId }
        val ids = list.map { it.postId }
        val counts = posts.reactionCounts(ids)
        val mine = caller?.let { posts.myReactions(ids, it) }.orEmpty()
        return list.map { post ->
            val reply =
                post.replyTo?.let { id ->
                    replies[id]?.let {
                        ReplyView(id, people.profileOf(it.authorId).displayName, it.text?.take(QUOTE), it.isRemoved)
                    }
                }
            PostView(
                post = post,
                author = people.profileOf(post.authorId),
                authorRole = roles[post.authorId],
                media =
                    if (post.isRemoved) {
                        emptyList()
                    } else {
                        post.mediaIds.mapNotNull { files[it] }.map {
                            PostMediaView(it, storage.downloadUrl(mediaKey(it.mediaId), config.mediaUrlLifetime))
                        }
                    },
                replyTo = reply,
                reactions = counts[post.postId].orEmpty(),
                myReaction = mine[post.postId],
                mine = caller == post.authorId,
            )
        }
    }

    suspend fun one(spaceId: SpaceId, post: Post, caller: UserId?): PostView = of(spaceId, listOf(post), caller).single()

    private companion object {
        const val QUOTE = 120
    }
}

/** A post as frames carry it: the same shape as SP28's `Post`. */
fun PostView.toFrame(): FramePost =
    FramePost(
        postId = post.postId,
        spaceId = post.spaceId,
        clientPostId = post.clientPostId.toString().takeIf { mine },
        author = FramePerson(author.userId, author.displayName, author.handle, author.avatarUrl, authorRole?.name),
        text = post.text,
        media =
            media.map {
                FrameMedia(
                    it.media.mediaId.toString(),
                    it.media.kind.name,
                    it.media.contentType,
                    it.media.sizeBytes,
                    it.media.width,
                    it.media.height,
                    it.media.durationMs,
                    it.url,
                )
            },
        replyTo = replyTo?.let { FrameReplyTo(it.postId, it.authorName, it.text, it.removed) },
        reactions = reactions.toFrame(),
        myReaction = myReaction,
        createdAt = post.createdAt.toString(),
        removed = removedFrame(post),
    )

fun List<ReactionCount>.toFrame(): List<FrameReaction> = map { FrameReaction(it.emoji, it.count) }

fun removedFrame(post: Post): FrameRemoved? =
    post.removedAt?.let { at -> FrameRemoved(post.removedBy?.name ?: "MODERATOR", at.toString()) }
