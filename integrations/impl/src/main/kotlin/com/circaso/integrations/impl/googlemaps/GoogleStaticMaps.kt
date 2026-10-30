package com.circaso.integrations.impl.googlemaps

import com.circaso.infrastructure.api.CircuitOpenException
import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.MapPalette
import com.circaso.integrations.api.MapPicture
import com.circaso.integrations.api.StaticMapRequest
import com.circaso.integrations.api.StaticMaps
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.readRawBytes
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.Base64
import java.util.Locale
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Google's Maps Static API (decision 2026-10-30). The key and the URL-signing secret are server secrets; a signed URL
 * can't be reused with other parameters, so a leaked URL can't draw anything else on our account.
 *
 * The style hides shops, bus stops and icons so the app's pin is the one thing that stands out, and colours the
 * rest from [MapPalette]. Coordinates go to Google only here, never with who asked.
 */
class GoogleStaticMaps(private val http: HttpClient, private val key: String, signingSecret: String?) : StaticMaps {
    private val log = LoggerFactory.getLogger(GoogleStaticMaps::class.java)
    private val signer = signingSecret?.takeIf { it.isNotBlank() }?.let { UrlSigner(it) }

    override suspend fun render(request: StaticMapRequest): Resource<MapPicture> {
        val path = signedPath(request)
        return try {
            val response = http.get(BASE_URL + path) { operation("static_map") }
            val type = response.contentType()
            if (!response.status.isSuccess() || type?.contentType != "image") {
                log.warn("google static map refused: status={}", response.status.value)
                Resource.error(IntegrationError.Unavailable(VENDOR))
            } else {
                Resource.success(MapPicture(response.readRawBytes(), type.toString()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: CircuitOpenException) {
            Resource.error(IntegrationError.Unavailable(VENDOR))
        } catch (e: Exception) {
            log.warn("google static map unreachable: error={}", e::class.simpleName)
            Resource.error(IntegrationError.Unavailable(VENDOR))
        }
    }

    /** The path and query Google signs, with `signature` last when there is a secret. */
    internal fun signedPath(request: StaticMapRequest): String {
        val unsigned = PATH + "?" + query(request) + "&key=" + key
        return signer?.let { unsigned + "&signature=" + it.sign(unsigned) } ?: unsigned
    }

    private fun query(r: StaticMapRequest): String =
        buildString {
            append("center=").append(coordinate(r.latitude)).append(',').append(coordinate(r.longitude))
            append("&zoom=").append(r.zoom)
            append("&size=").append(r.widthPoints).append('x').append(r.heightPoints)
            append("&scale=2&format=png")
            styles(r.palette).forEach { append("&style=").append(it.replace("|", "%7C")) }
        }

    private fun coordinate(value: Double) = String.format(Locale.ROOT, "%.5f", value)

    private fun styles(p: MapPalette) =
        listOf(
            "element:geometry|color:0x${p.land}",
            "element:labels.icon|visibility:off",
            "element:labels.text.fill|color:0x${p.label}",
            "element:labels.text.stroke|color:0x${p.land}",
            "feature:poi|visibility:off",
            "feature:transit|visibility:off",
            "feature:administrative|element:geometry|visibility:off",
            "feature:road|element:geometry|color:0x${p.road}",
            "feature:road.highway|element:geometry|color:0x${p.highway}",
            "feature:road.local|element:labels|visibility:off",
            "feature:water|element:geometry|color:0x${p.water}",
            "feature:water|element:labels|visibility:off",
        )

    companion object {
        const val BASE_URL = "https://maps.googleapis.com"
        private const val PATH = "/maps/api/staticmap"
        private const val VENDOR = "Google Maps"
    }
}

/**
 * Google's URL signing: HMAC-SHA1 over the path and query with the URL-safe base64 secret from the Cloud console,
 * written back URL-safe base64.
 */
internal class UrlSigner(secret: String) {
    private val key = Base64.getUrlDecoder().decode(secret)

    fun sign(pathAndQuery: String): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(key, "HmacSHA1")) }
        return Base64.getUrlEncoder().encodeToString(mac.doFinal(pathAndQuery.toByteArray(Charsets.UTF_8)))
    }
}
