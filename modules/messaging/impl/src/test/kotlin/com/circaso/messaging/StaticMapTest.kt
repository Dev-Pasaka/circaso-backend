package com.circaso.messaging

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MapPicture
import com.circaso.integrations.api.StaticMapRequest
import com.circaso.integrations.api.StaticMaps
import com.circaso.messaging.domain.errors.MessagingError
import com.circaso.messaging.domain.models.MapColors
import com.circaso.messaging.domain.models.StaticMapCommand
import com.circaso.messaging.domain.usecases.GetStaticMap
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** MAP1: the use case checks ranges, hands a valid request on, and turns the vendor's failure into our code. */
class StaticMapTest {
    private class Maps : StaticMaps {
        var picture: MapPicture? = MapPicture(byteArrayOf(1), "image/png")
        val asked = mutableListOf<StaticMapRequest>()

        override suspend fun render(request: StaticMapRequest): Resource<MapPicture> {
            asked += request
            return picture?.let { Resource.success(it) } ?: Resource.error(IntegrationError.Unavailable("maps"))
        }
    }

    private val maps = Maps()
    private val getStaticMap = GetStaticMap(maps)
    private val colors = MapColors("F2F2F7", "FFFFFF", "DCDCE0", "dcdce0", "636366")
    private val nairobi = StaticMapCommand(-1.29207, 36.82195, 16, 240, 120, colors)

    @Test
    fun aValidRequestIsDrawn() =
        runBlocking {
            assertIs<Resource.Success<*>>(getStaticMap(nairobi))
            val asked = maps.asked.single()
            assertEquals(-1.29207, asked.latitude)
            assertEquals(240, asked.widthPoints)
            assertEquals("F2F2F7", asked.palette.land)
        }

    @Test
    fun outOfRangeValuesAreRefusedWithoutAskingTheVendor() =
        runBlocking {
            val bad =
                listOf(
                    nairobi.copy(latitude = 91.0),
                    nairobi.copy(longitude = -181.0),
                    nairobi.copy(zoom = 2),
                    nairobi.copy(zoom = 21),
                    nairobi.copy(widthPoints = 641),
                    nairobi.copy(heightPoints = 8),
                    nairobi.copy(colors = colors.copy(water = "blue")),
                    nairobi.copy(colors = colors.copy(label = "#636366")),
                )
            bad.forEach {
                assertEquals(
                    MessagingError.MapInvalid,
                    assertIs<Resource.Error>(getStaticMap(it)).error,
                    "$it",
                )
            }
            assertTrue(maps.asked.isEmpty())
        }

    @Test
    fun aVendorFailureIsMapUnavailable() =
        runBlocking {
            maps.picture = null
            assertEquals(MessagingError.MapUnavailable, assertIs<Resource.Error>(getStaticMap(nairobi)).error)
        }
}
