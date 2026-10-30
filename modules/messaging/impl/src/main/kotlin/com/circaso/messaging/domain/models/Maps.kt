package com.circaso.messaging.domain.models

/** A location message's preview picture: the map around one point, coloured to the asking phone's theme. */
data class StaticMapCommand(
    val latitude: Double,
    val longitude: Double,
    val zoom: Int,
    val widthPoints: Int,
    val heightPoints: Int,
    val colors: MapColors,
)

/** `RRGGBB` colours for the land, ordinary roads, highways, water and label text. */
data class MapColors(
    val land: String,
    val road: String,
    val highway: String,
    val water: String,
    val label: String,
)
