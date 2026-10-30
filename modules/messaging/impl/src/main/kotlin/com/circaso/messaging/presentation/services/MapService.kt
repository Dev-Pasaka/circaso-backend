package com.circaso.messaging.presentation.services

import com.circaso.integrations.api.MapPicture
import com.circaso.messaging.domain.models.StaticMapCommand
import com.circaso.messaging.domain.usecases.GetStaticMap
import com.circaso.shared.Resource

/** Location message previews, MAP1. */
class MapService(private val getStaticMap: GetStaticMap) {
    suspend fun staticMap(command: StaticMapCommand): Resource<MapPicture> = getStaticMap(command)
}
