package com.circaso.messaging.domain.usecases

import com.circaso.integrations.api.MapPalette
import com.circaso.integrations.api.MapPicture
import com.circaso.integrations.api.StaticMapRequest
import com.circaso.integrations.api.StaticMaps
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.StaticMapCommand
import com.circaso.shared.Resource

/**
 * MAP1 `GET /maps/static`: the preview picture for a location message (decision 2026-10-30). The vendor key stays
 * here; the phone sends only the point, the size and its theme's colours. Nothing is stored or logged about the
 * point: location messages are end-to-end encrypted, and this is the one place a point passes through us.
 */
class GetStaticMap(private val maps: StaticMaps) {
    suspend operator fun invoke(command: StaticMapCommand): Resource<MapPicture> {
        val colors = command.colors
        val valid =
            command.latitude in -90.0..90.0 &&
                command.longitude in -180.0..180.0 &&
                command.zoom in ZOOMS &&
                command.widthPoints in SIDES &&
                command.heightPoints in SIDES &&
                listOf(colors.land, colors.road, colors.highway, colors.water, colors.label).all(HEX::matches)
        if (!valid) return Resource.error(MessagingError.MapInvalid)

        val request =
            StaticMapRequest(
                latitude = command.latitude,
                longitude = command.longitude,
                zoom = command.zoom,
                widthPoints = command.widthPoints,
                heightPoints = command.heightPoints,
                palette = MapPalette(colors.land, colors.road, colors.highway, colors.water, colors.label),
            )
        return when (val picture = maps.render(request)) {
            is Resource.Success -> picture
            is Resource.Error -> Resource.error(MessagingError.MapUnavailable, picture.cause)
        }
    }

    private companion object {
        val ZOOMS = 3..20

        /** Google's largest side is 640 points; below 16 there is nothing to see. */
        val SIDES = 16..640
        val HEX = Regex("[0-9A-Fa-f]{6}")
    }
}
