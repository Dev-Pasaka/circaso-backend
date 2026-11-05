package com.circaso.spaces.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.PostId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.models.CreatePostCommand
import com.circaso.spaces.domain.models.MediaUpload
import com.circaso.spaces.domain.models.Page
import com.circaso.spaces.domain.models.PostView
import com.circaso.spaces.domain.models.PostsQuery
import com.circaso.spaces.domain.models.PrepareMediaCommand
import com.circaso.spaces.domain.models.ReactionResult
import com.circaso.spaces.domain.models.SpaceMedia
import com.circaso.spaces.domain.usecases.CompleteSpaceMedia
import com.circaso.spaces.domain.usecases.CreatePost
import com.circaso.spaces.domain.usecases.ListPosts
import com.circaso.spaces.domain.usecases.MarkRead
import com.circaso.spaces.domain.usecases.PrepareSpaceMedia
import com.circaso.spaces.domain.usecases.ReactToPost
import com.circaso.spaces.domain.usecases.RemovePost
import java.util.UUID

/** The space chat and its files: SP28-SP34. */
class PostService(
    private val listPosts: ListPosts,
    private val createPost: CreatePost,
    private val removePost: RemovePost,
    private val reactToPost: ReactToPost,
    private val markRead: MarkRead,
    private val prepareSpaceMedia: PrepareSpaceMedia,
    private val completeSpaceMedia: CompleteSpaceMedia,
) {
    suspend fun list(query: PostsQuery): Resource<Page<PostView>> = listPosts(query)

    suspend fun create(command: CreatePostCommand): Resource<PostView> = createPost(command)

    suspend fun remove(caller: UserId, spaceId: SpaceId, postId: PostId): Resource<PostView> = removePost(caller, spaceId, postId)

    suspend fun react(
        caller: UserId,
        spaceId: SpaceId,
        postId: PostId,
        emoji: String?,
    ): Resource<ReactionResult> = reactToPost(caller, spaceId, postId, emoji)

    suspend fun read(caller: UserId, spaceId: SpaceId, postId: PostId): Resource<Int> = markRead(caller, spaceId, postId)

    suspend fun prepareMedia(command: PrepareMediaCommand): Resource<MediaUpload> = prepareSpaceMedia(command)

    suspend fun completeMedia(caller: UserId, mediaId: UUID): Resource<SpaceMedia> = completeSpaceMedia(caller, mediaId)
}
