package com.circaso.spaces.presentation.controllers

import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.spaces.api.PostId
import com.circaso.spaces.domain.models.PostsQuery
import com.circaso.spaces.presentation.dto.CreatePostRequest
import com.circaso.spaces.presentation.dto.MediaCompletedResponse
import com.circaso.spaces.presentation.dto.MediaUploadResponse
import com.circaso.spaces.presentation.dto.PostsResponse
import com.circaso.spaces.presentation.dto.PrepareMediaRequest
import com.circaso.spaces.presentation.dto.ReactionRequest
import com.circaso.spaces.presentation.dto.ReactionResponse
import com.circaso.spaces.presentation.dto.ReadRequest
import com.circaso.spaces.presentation.dto.UnreadResponse
import com.circaso.spaces.domain.support.toFrame
import com.circaso.spaces.api.FramePost
import com.circaso.spaces.presentation.mappers.parseLimit
import com.circaso.spaces.presentation.mappers.parsePostId
import com.circaso.spaces.presentation.mappers.parseSpaceId
import com.circaso.spaces.presentation.mappers.parseUuid
import com.circaso.spaces.presentation.mappers.toCommand
import com.circaso.spaces.presentation.mappers.toResponse
import com.circaso.spaces.presentation.services.PostService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import com.circaso.web.userId
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/** The space chat (Spaces API, SP28-SP32) and its files (SP33, SP34). Not end-to-end encrypted. */
fun Route.postController(posts: PostService, pageDefault: Int, pageMax: Int) {
    route("/spaces") {
        authenticate(Scope.APP) {
            mediaRoutes(posts)
            route("/{spaceId}/posts") { postRoutes(posts, pageDefault, pageMax) }
            post("/{spaceId}/read") {
                val request = call.receive<ReadRequest>()
                val result =
                    parseSpaceId(call.parameters["spaceId"]).flatMap { space ->
                        parsePostId(request.postId).flatMap { posts.read(call.userId(), space, it) }
                    }
                call.respondDefault(result.toDefaultResponse { UnreadResponse(it) })
            }.docs<ReadRequest, UnreadResponse>(
                "SP32 · Mark the chat read up to a post",
                TAG,
                listOf("spaces.not_member", "spaces.post_not_found", MALFORMED),
                notes = "$SCOPE $IDEMPOTENT Moves your marker forward, never back.",
            )
        }
    }
}

private fun Route.postRoutes(posts: PostService, pageDefault: Int, pageMax: Int) {
    get {
        val query = call.request.queryParameters
        val before = query["before"]?.let { PostId.parse(it) ?: null }
        val after = query["after"]?.let { PostId.parse(it) ?: null }
        val shape: Resource<Unit> =
            if ((query["before"] != null && before == null) || (query["after"] != null && after == null) || (before != null && after != null)) {
                Resource.error(com.circaso.shared.SharedError.Malformed("before", "one valid post id, before or after"))
            } else {
                Resource.success(Unit)
            }
        val result =
            shape.flatMap {
                parseSpaceId(call.parameters["spaceId"]).flatMap { space ->
                    parseLimit(query["limit"], pageDefault, pageMax).flatMap { limit ->
                        posts.list(PostsQuery(call.userId(), space, before, after, limit))
                    }
                }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, PostsResponse>(
        "SP28 · Posts in a space chat",
        TAG,
        listOf("spaces.members_only", "spaces.membership_ended", "spaces.banned", NOT_FOUND, MALFORMED),
        notes =
            "$SCOPE Newest first with `before` (or neither), oldest first with `after`; `next` continues the same " +
                "way. Visitors of a public space read the latest 20 only.",
    )

    post {
        val request = call.receive<CreatePostRequest>()
        val result = parseSpaceId(call.parameters["spaceId"]).flatMap { space -> request.toCommand(call.userId(), space).flatMap { posts.create(it) } }
        call.respondDefault(result.toDefaultResponse { it.toFrame() })
    }.docs<CreatePostRequest, FramePost>(
        "SP29 · Post in a space chat",
        TAG,
        listOf(
            "spaces.not_member",
            "spaces.membership_ended",
            "spaces.posting_restricted",
            "spaces.muted",
            "spaces.banned",
            "spaces.post_invalid",
            "spaces.post_not_found",
            "spaces.media_not_found",
            "spaces.too_many_posts",
            MALFORMED,
        ),
        notes = "$SCOPE $IDEMPOTENT Queueable: `clientPostId` makes a repeat return the first. Members get `space.post_new`.",
    )

    delete("{postId}") {
        val result =
            parseSpaceId(call.parameters["spaceId"]).flatMap { space ->
                parsePostId(call.parameters["postId"]).flatMap { posts.remove(call.userId(), space, it) }
            }
        call.respondDefault(result.toDefaultResponse { it.toFrame() })
    }.docs<NoBody, FramePost>(
        "SP30 · Remove a post",
        TAG,
        listOf("spaces.post_not_found", "spaces.not_moderator", MALFORMED),
        notes = "$SCOPE $IDEMPOTENT Your own, or any for moderators and up. A tombstone stays.",
    )

    put("{postId}/reaction") {
        val request = call.receive<ReactionRequest>()
        val result =
            parseSpaceId(call.parameters["spaceId"]).flatMap { space ->
                parsePostId(call.parameters["postId"]).flatMap { posts.react(call.userId(), space, it, request.emoji) }
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<ReactionRequest, ReactionResponse>(
        "SP31 · React to a post",
        TAG,
        listOf("spaces.reaction_invalid", "spaces.post_not_found", "spaces.not_member", MALFORMED),
        notes = "$SCOPE $IDEMPOTENT One emoji per person per post; null clears it. Queueable.",
    )
}

private fun Route.mediaRoutes(posts: PostService) {
    post("media") {
        val request = call.receive<PrepareMediaRequest>()
        val result = request.toCommand(call.userId()).flatMap { posts.prepareMedia(it) }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<PrepareMediaRequest, MediaUploadResponse>(
        "SP33 · Upload a file for a space or a post",
        TAG,
        listOf("spaces.media_invalid", MALFORMED),
        notes = "$SCOPE $IDEMPOTENT PUT the bytes to `uploadUrl` with exactly `uploadHeaders` within 1 hour. Not encrypted.",
    )

    post("media/{mediaId}/complete") {
        val result = parseUuid(call.parameters["mediaId"], "mediaId").flatMap { posts.completeMedia(call.userId(), it) }
        call.respondDefault(result.toDefaultResponse { MediaCompletedResponse(it.mediaId.toString(), it.sizeBytes) })
    }.docs<NoBody, MediaCompletedResponse>(
        "SP34 · Finish a file upload",
        TAG,
        listOf("spaces.media_not_found", "spaces.media_invalid", MALFORMED),
    )
}
