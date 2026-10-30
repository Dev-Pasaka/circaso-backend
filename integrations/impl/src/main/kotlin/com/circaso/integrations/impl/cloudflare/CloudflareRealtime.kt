package com.circaso.integrations.impl.cloudflare

import com.circaso.infrastructure.api.operation
import com.circaso.integrations.api.CallMedia
import com.circaso.integrations.api.IceServer
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LocalTrack
import com.circaso.integrations.api.PulledTrack
import com.circaso.integrations.api.PulledTracks
import com.circaso.integrations.api.PushedTracks
import com.circaso.integrations.api.RemoteTrack
import com.circaso.shared.Resource
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

private const val VENDOR = "Cloudflare Realtime"
private const val STUN = "stun:stun.cloudflare.com:3478"

/** Cloudflare's bodies: unknown fields ignored, a null field left out (a pull sends no `sessionDescription`). */
private val RealtimeJson =
    Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

/**
 * Turn credentials asked for: Cloudflare's TURN key, and the token that may generate credentials for it. Without
 * one, phones get STUN only, which works on most networks but not behind a symmetric NAT.
 */
data class TurnKey(val keyId: String, val token: String)

/**
 * Calls' SFU on Cloudflare Realtime (`https://rtc.live.cloudflare.com/v1/apps/{appId}`, API version 2024-05-21),
 * with the app's secret as a bearer token. One session per participant's peer connection: `POST /sessions/new`,
 * `POST /sessions/{id}/tracks/new` to push local tracks (with the phone's offer) or pull remote ones (the SFU may
 * offer back), `PUT /sessions/{id}/renegotiate` with the phone's answer, `PUT /sessions/{id}/tracks/close` with
 * `force: true` when a participant leaves (the server has no offer of its own to negotiate the close with).
 *
 * [http] is the shared `HttpClients.named("cloudflare-realtime")` client. Our credentials refused (`401`, `403`),
 * `429`, `5xx`, a timeout or an open circuit is `Unavailable`; any other `4xx` (a malformed SDP, an unknown session)
 * is `Rejected`. SDP, session ids and tokens are never logged; only the operation and the status are.
 *
 * TURN, when [turn] is set: `POST /v1/turn/keys/{keyId}/credentials/generate-ice-servers` with a 24-hour `ttl`,
 * cached on this node until an hour before they expire, so every call doesn't mint new ones. URLs on port 53 are
 * dropped (browsers and some phones time out on them, as Cloudflare's docs warn). A TURN failure falls back to STUN.
 */
class CloudflareRealtime(
    private val appId: String,
    private val appToken: String,
    private val http: HttpClient,
    private val turn: TurnKey? = null,
    private val clock: Clock = Clock.systemUTC(),
) : CallMedia {
    private val log = LoggerFactory.getLogger(CloudflareRealtime::class.java)
    private val turnLock = Mutex()
    private var turnServers: List<IceServer>? = null
    private var turnExpiresAt: Instant = Instant.EPOCH
    private val app = "/v1/apps/$appId"

    override suspend fun newSession(): Resource<String> =
        call(
            "new_session",
            NewSessionAnswer.serializer(),
            { prepare -> http.post("$app/sessions/new") { prepare() } },
        ) { answer -> answer.sessionId?.let { Resource.success(it) } }

    override suspend fun pushTracks(
        sessionId: String,
        offerSdp: String,
        tracks: List<LocalTrack>,
    ): Resource<PushedTracks> {
        val body =
            TracksBody(
                sessionDescription = SessionDescription("offer", offerSdp),
                tracks = tracks.map { TrackObject(location = "local", mid = it.mid, trackName = it.trackName) },
            )
        return call(
            "push_tracks",
            TracksAnswer.serializer(),
            { prepare -> send(HttpMethod.Post, "$app/sessions/$sessionId/tracks/new", tracksJson(body), prepare) },
        ) { answer ->
            answer.sessionDescription
                ?.takeIf { it.type == "answer" }
                ?.let { Resource.success(PushedTracks(it.sdp)) }
        }
    }

    override suspend fun pullTracks(sessionId: String, tracks: List<RemoteTrack>): Resource<PulledTracks> {
        val body =
            TracksBody(
                tracks =
                    tracks.map {
                        TrackObject(location = "remote", sessionId = it.sessionId, trackName = it.trackName)
                    },
            )
        return call(
            "pull_tracks",
            TracksAnswer.serializer(),
            { prepare -> send(HttpMethod.Post, "$app/sessions/$sessionId/tracks/new", tracksJson(body), prepare) },
        ) { answer ->
            // Cloudflare may leave a pulled track's sessionId out of its answer; the name we asked for identifies it.
            val askedFrom = tracks.associate { it.trackName to it.sessionId }
            val pulled =
                answer.tracks.mapNotNull { track ->
                    val name = track.trackName ?: return@mapNotNull null
                    val from = track.sessionId ?: askedFrom[name] ?: return@mapNotNull null
                    PulledTrack(name, from, track.mid.takeIf { track.errorCode == null })
                }
            Resource.success(
                PulledTracks(
                    offerSdp = answer.sessionDescription?.takeIf { it.type == "offer" }?.sdp,
                    requiresRenegotiation = answer.requiresImmediateRenegotiation,
                    tracks = pulled,
                ),
            )
        }
    }

    override suspend fun renegotiate(sessionId: String, answerSdp: String): Resource<Unit> {
        val body = RenegotiateBody(SessionDescription("answer", answerSdp))
        return call(
            "renegotiate",
            ErrorAnswer.serializer(),
            { prepare ->
                val json = RealtimeJson.encodeToString(RenegotiateBody.serializer(), body)
                send(HttpMethod.Put, "$app/sessions/$sessionId/renegotiate", json, prepare)
            },
        ) { Resource.success(Unit) }
    }

    override suspend fun closeTracks(sessionId: String, mids: List<String>): Resource<Unit> {
        if (mids.isEmpty()) return Resource.success(Unit)
        val body = CloseBody(mids.map { CloseTrack(it) }, force = true)
        return call(
            "close_tracks",
            ErrorAnswer.serializer(),
            { prepare ->
                val json = RealtimeJson.encodeToString(CloseBody.serializer(), body)
                send(HttpMethod.Put, "$app/sessions/$sessionId/tracks/close", json, prepare)
            },
        ) { Resource.success(Unit) }
    }

    override suspend fun iceServers(): List<IceServer> {
        val stun = IceServer(listOf(STUN))
        val key = turn ?: return listOf(stun)
        return listOf(stun) + turnServers(key)
    }

    private suspend fun turnServers(key: TurnKey): List<IceServer> =
        turnLock.withLock {
            val now = clock.instant()
            turnServers?.takeIf { now.isBefore(turnExpiresAt.minus(TURN_REFRESH_BEFORE)) }?.let { return it }
            val fresh = generateTurn(key) ?: return turnServers?.takeIf { now.isBefore(turnExpiresAt) }.orEmpty()
            turnServers = fresh
            turnExpiresAt = now.plus(TURN_TTL)
            fresh
        }

    private suspend fun generateTurn(key: TurnKey): List<IceServer>? {
        val body = RealtimeJson.encodeToString(TurnBody.serializer(), TurnBody(TURN_TTL.seconds))
        return try {
            val response =
                http.post("/v1/turn/keys/${key.keyId}/credentials/generate-ice-servers") {
                    operation("turn_credentials")
                    header(HttpHeaders.Authorization, "Bearer ${key.token}")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            val status = response.status.value
            if (status !in 200..299) {
                log.warn("cloudflare turn refused: status={}", status)
                return null
            }
            decode(TurnAnswer.serializer(), response.bodyAsText())
                ?.iceServers
                ?.filter { it.username != null && it.credential != null }
                ?.map { server -> IceServer(server.urls.filterNot(::onPort53), server.username, server.credential) }
                ?.filter { it.urls.isNotEmpty() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log.warn("cloudflare turn unreachable: error={}", e::class.simpleName)
            null
        }
    }

    private suspend fun send(
        method: HttpMethod,
        path: String,
        json: String,
        prepare: HttpRequestBuilder.() -> Unit,
    ): HttpResponse =
        http.request(path) {
            this.method = method
            prepare()
            contentType(ContentType.Application.Json)
            setBody(json)
        }

    private fun tracksJson(body: TracksBody): String = RealtimeJson.encodeToString(TracksBody.serializer(), body)

    /**
     * One request to the SFU: [send] with the operation name and the app's token, then [read] the decoded answer
     * (null when the answer lacks what it needs, which is `Unavailable`).
     */
    private suspend fun <A : HasError, T> call(
        operation: String,
        serializer: KSerializer<A>,
        send: suspend (prepare: HttpRequestBuilder.() -> Unit) -> HttpResponse,
        read: (A) -> Resource<T>?,
    ): Resource<T> {
        val (status, text) =
            try {
                val response =
                    send {
                        operation(operation)
                        header(HttpHeaders.Authorization, "Bearer $appToken")
                    }
                response.status.value to response.bodyAsText()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                log.warn("cloudflare realtime unreachable: operation={} error={}", operation, e::class.simpleName)
                return Resource.error(IntegrationError.Unavailable(VENDOR), e)
            }
        failure(operation, status)?.let { return Resource.error(it) }
        val answer = decode(serializer, text.ifBlank { "{}" })
        if (answer?.errorCode != null) {
            log.warn("cloudflare realtime refused: operation={} error={}", operation, answer.errorCode)
            return Resource.error(IntegrationError.Rejected("$VENDOR refused the request (${answer.errorCode})."))
        }
        return answer?.let(read) ?: run {
            log.warn("cloudflare realtime answered without what it should: operation={}", operation)
            Resource.error(IntegrationError.Unavailable(VENDOR))
        }
    }

    private fun failure(operation: String, status: Int): IntegrationError? =
        when {
            status in 200..299 -> {
                null
            }

            status == 401 || status == 403 -> {
                log.error("cloudflare realtime refused our credentials: operation={} status={}", operation, status)
                IntegrationError.Unavailable(VENDOR)
            }

            status == 429 || status >= 500 -> {
                log.warn("cloudflare realtime unavailable: operation={} status={}", operation, status)
                IntegrationError.Unavailable(VENDOR)
            }

            else -> {
                log.warn("cloudflare realtime refused: operation={} status={}", operation, status)
                IntegrationError.Rejected("$VENDOR refused the request (status $status).")
            }
        }

    private fun <T> decode(serializer: KSerializer<T>, text: String): T? =
        runCatching { RealtimeJson.decodeFromString(serializer, text) }.getOrNull()

    private companion object {
        val TURN_TTL: Duration = Duration.ofHours(24)
        val TURN_REFRESH_BEFORE: Duration = Duration.ofHours(1)

        val PORT_53 = Regex(":53(\\?|$)")

        fun onPort53(url: String): Boolean = PORT_53.containsMatchIn(url)
    }
}

// ---- Cloudflare Realtime bodies (developers.cloudflare.com/realtime/static/realtime-api-2024-05-21.yaml) ----

/** Every answer may carry a request-level error instead of what was asked. */
private interface HasError {
    val errorCode: String?
}

@Serializable
private data class SessionDescription(val type: String, val sdp: String)

@Serializable
private data class TrackObject(
    val location: String,
    val mid: String? = null,
    val sessionId: String? = null,
    val trackName: String,
)

@Serializable
private data class TracksBody(val sessionDescription: SessionDescription? = null, val tracks: List<TrackObject>)

@Serializable
private data class RenegotiateBody(val sessionDescription: SessionDescription)

@Serializable
private data class CloseTrack(val mid: String)

@Serializable
private data class CloseBody(val tracks: List<CloseTrack>, val force: Boolean)

@Serializable
private data class NewSessionAnswer(val sessionId: String? = null, override val errorCode: String? = null) : HasError

@Serializable
private data class TrackAnswer(
    val mid: String? = null,
    val sessionId: String? = null,
    val trackName: String? = null,
    val errorCode: String? = null,
)

@Serializable
private data class TracksAnswer(
    val sessionDescription: SessionDescription? = null,
    val requiresImmediateRenegotiation: Boolean = false,
    val tracks: List<TrackAnswer> = emptyList(),
    override val errorCode: String? = null,
) : HasError

@Serializable
private data class ErrorAnswer(override val errorCode: String? = null) : HasError

@Serializable
private data class TurnBody(val ttl: Long)

@Serializable
private data class TurnServer(
    val urls: List<String> = emptyList(),
    val username: String? = null,
    val credential: String? = null,
)

@Serializable
private data class TurnAnswer(val iceServers: List<TurnServer> = emptyList())
