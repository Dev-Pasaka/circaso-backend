package com.circaso.spaces.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.SpaceId
import com.circaso.spaces.domain.models.CreateSpaceCommand
import com.circaso.spaces.domain.models.HandleCheck
import com.circaso.spaces.domain.models.MySpaceView
import com.circaso.spaces.domain.models.Page
import com.circaso.spaces.domain.models.SearchView
import com.circaso.spaces.domain.models.SpaceView
import com.circaso.spaces.domain.models.SuggestionView
import com.circaso.spaces.domain.models.Topic
import com.circaso.spaces.domain.models.UpdateSpaceCommand
import com.circaso.spaces.domain.models.UpdatedSpace
import com.circaso.spaces.domain.usecases.CheckHandle
import com.circaso.spaces.domain.usecases.CreateSpace
import com.circaso.spaces.domain.usecases.DeleteSpace
import com.circaso.spaces.domain.usecases.GetSpace
import com.circaso.spaces.domain.usecases.GetSpaceByHandle
import com.circaso.spaces.domain.usecases.ListMySpaces
import com.circaso.spaces.domain.usecases.SearchSpaces
import com.circaso.spaces.domain.usecases.SuggestSpaces
import com.circaso.spaces.domain.usecases.UpdateSpace

/** Spaces themselves: SP1-SP9. */
class SpaceService(
    private val checkHandle: CheckHandle,
    private val createSpace: CreateSpace,
    private val getSpace: GetSpace,
    private val getSpaceByHandle: GetSpaceByHandle,
    private val updateSpace: UpdateSpace,
    private val deleteSpace: DeleteSpace,
    private val listMySpaces: ListMySpaces,
    private val suggestSpaces: SuggestSpaces,
    private val searchSpaces: SearchSpaces,
) {
    suspend fun checkHandle(handle: String): Resource<HandleCheck> = checkHandle.invoke(handle)

    suspend fun create(command: CreateSpaceCommand): Resource<SpaceView> = createSpace(command)

    suspend fun get(caller: UserId, spaceId: SpaceId): Resource<SpaceView> = getSpace(caller, spaceId)

    suspend fun byHandle(caller: UserId, handle: String): Resource<SpaceView> = getSpaceByHandle(caller, handle)

    suspend fun update(command: UpdateSpaceCommand): Resource<UpdatedSpace> = updateSpace(command)

    suspend fun delete(caller: UserId, spaceId: SpaceId): Resource<Unit> = deleteSpace(caller, spaceId)

    suspend fun mine(
        caller: UserId,
        offset: Int,
        limit: Int,
    ): Resource<Page<MySpaceView>> = listMySpaces(caller, offset, limit)

    suspend fun suggested(
        caller: UserId,
        country: String?,
        topic: Topic?,
        offset: Int,
        limit: Int,
    ): Resource<Page<SuggestionView>> = suggestSpaces(caller, country, topic, offset, limit)

    suspend fun search(
        query: String,
        topic: Topic?,
        offset: Int,
        limit: Int,
    ): Resource<SearchView> = searchSpaces(query, topic, offset, limit)
}
