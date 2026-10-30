package com.circaso.messaging.presentation.mappers

import com.circaso.messaging.domain.models.MapColors
import com.circaso.messaging.domain.models.StaticMapCommand
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import io.ktor.http.Parameters

private fun malformed(field: String) = Resource.error(SharedError.Malformed(field, "is missing or not a number"))

/** MAP1's query: `lat`, `lng`, `zoom`, `w`, `h` and the five colours. Ranges are the use case's to check. */
fun Parameters.toStaticMapCommand(): Resource<StaticMapCommand> {
    val lat = this["lat"]?.toDoubleOrNull() ?: return malformed("lat")
    val lng = this["lng"]?.toDoubleOrNull() ?: return malformed("lng")
    val zoom = this["zoom"]?.toIntOrNull() ?: return malformed("zoom")
    val width = this["w"]?.toIntOrNull() ?: return malformed("w")
    val height = this["h"]?.toIntOrNull() ?: return malformed("h")
    val colors =
        MapColors(
            land = this["land"] ?: return malformed("land"),
            road = this["road"] ?: return malformed("road"),
            highway = this["highway"] ?: return malformed("highway"),
            water = this["water"] ?: return malformed("water"),
            label = this["label"] ?: return malformed("label"),
        )
    return Resource.success(StaticMapCommand(lat, lng, zoom, width, height, colors))
}
