package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MapPicture
import com.circaso.integrations.api.StaticMapRequest
import com.circaso.integrations.api.StaticMaps
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.util.concurrent.CopyOnWriteArrayList

/** Tests: answers [picture] (or unavailable when null) and records every request in [asked]. */
class FakeStaticMaps : StaticMaps {
    var picture: MapPicture? = MapPicture(byteArrayOf(1, 2, 3), "image/png")
    val asked: MutableList<StaticMapRequest> = CopyOnWriteArrayList()

    override suspend fun render(request: StaticMapRequest): Resource<MapPicture> {
        asked += request
        return picture?.let { Resource.success(it) } ?: Resource.error(IntegrationError.Unavailable("Fake maps"))
    }
}

/**
 * Local runs without a Google key: logs the size asked for (never the coordinates) and answers unavailable, so the
 * app keeps its drawn placeholder tile.
 */
class LogStaticMaps : StaticMaps {
    private val log = LoggerFactory.getLogger(LogStaticMaps::class.java)

    override suspend fun render(request: StaticMapRequest): Resource<MapPicture> {
        log.info("static map asked for: {}x{} at zoom {}", request.widthPoints, request.heightPoints, request.zoom)
        return Resource.error(IntegrationError.Unavailable("Maps (log provider)"))
    }
}
