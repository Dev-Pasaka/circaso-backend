package com.circaso.integrations.api

import com.circaso.shared.Resource

/**
 * A small map picture around one point, for the preview inside a location message (decision 2026-10-30). The key
 * that pays for it stays on the server: the app asks us, never the vendor, so it can't be lifted from the app.
 */
interface StaticMaps {
    /** A PNG of [request], or [IntegrationError.Unavailable] when the vendor is down or not configured. */
    suspend fun render(request: StaticMapRequest): Resource<MapPicture>
}

/**
 * The map around [latitude], [longitude] at [zoom], [widthPoints] × [heightPoints] points drawn at 2× for sharp
 * phones. [palette] colours it to the app's theme; there is no marker (the app draws its own pin on top).
 */
data class StaticMapRequest(
    val latitude: Double,
    val longitude: Double,
    val zoom: Int,
    val widthPoints: Int,
    val heightPoints: Int,
    val palette: MapPalette,
)

/** Colours as `RRGGBB`: the land, ordinary roads, highways, water and label text. */
data class MapPalette(
    val land: String,
    val road: String,
    val highway: String,
    val water: String,
    val label: String,
)

class MapPicture(val bytes: ByteArray, val contentType: String)
