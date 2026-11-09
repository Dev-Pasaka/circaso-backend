package com.circaso.integrations.impl.cloudflare

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.LocalTrack
import com.circaso.integrations.api.PulledTracks
import com.circaso.integrations.api.PushedTracks
import com.circaso.integrations.api.RemoteTrack
import com.circaso.integrations.impl.FakeHttpClients
import com.circaso.integrations.impl.FakeHttpClients.Companion.json
import com.circaso.integrations.impl.text
import com.circaso.shared.Resource
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CloudflareRealtimeTest {
    @Serializable
    private data class Description(val type: String, val sdp: String)

    @Serializable
    private data class Track(
        val location: String? = null,
        val mid: String? = null,
        val sessionId: String? = null,
        val trackName: String? = null,
    )

    @Serializable
    private data class TracksSent(val sessionDescription: Description? = null, val tracks: List<Track> = emptyList())

    @Serializable
    private data class CloseSent(val tracks: List<Track>, val force: Boolean)

    private val reader = Json { ignoreUnknownKeys = true }
    private var status = 200
    private var reply = """{"sessionId":"s1"}"""
    private var down = false
    private val clients = FakeHttpClients { _ -> if (down) throw IOException("down") else json(reply, status) }
    private val http = clients.named("cloudflare-realtime") { baseUrl = "https://rtc.live.cloudflare.com" }
    private val media = CloudflareRealtime("app1", "app-secret", http)

    @Test
    fun `a new session is posted to the app with its secret as a bearer token`() =
        runBlocking<Unit> {
            val session = assertIs<Resource.Success<String>>(media.newSession()).data

            assertEquals("s1", session)
            val request = clients.last
            assertEquals(HttpMethod.Post, request.method)
            assertEquals("https://rtc.live.cloudflare.com/v1/apps/app1/sessions/new", request.url.toString())
            assertEquals("Bearer app-secret", request.headers[HttpHeaders.Authorization])
        }

    @Test
    fun `pushing local tracks sends the offer and returns the answer`() =
        runBlocking<Unit> {
            reply =
                """{"requiresImmediateRenegotiation":false,"tracks":[{"trackName":"audio-u1","mid":"0"}],""" +
                """"sessionDescription":{"type":"answer","sdp":"v=0 answer"}}"""

            val pushed = media.pushTracks("s1", "v=0 offer", listOf(LocalTrack("0", "audio-u1")))

            assertEquals("v=0 answer", assertIs<Resource.Success<PushedTracks>>(pushed).data.answerSdp)
            val request = clients.last
            assertEquals("https://rtc.live.cloudflare.com/v1/apps/app1/sessions/s1/tracks/new", request.url.toString())
            val sent = reader.decodeFromString<TracksSent>(request.text())
            assertEquals(Description("offer", "v=0 offer"), sent.sessionDescription)
            assertEquals(listOf(Track("local", "0", null, "audio-u1")), sent.tracks)
        }

    @Test
    fun `pulling remote tracks returns the SFU's offer and each track's mid`() =
        runBlocking<Unit> {
            reply =
                """{"requiresImmediateRenegotiation":true,"tracks":[""" +
                """{"sessionId":"s2","trackName":"audio-u2","mid":"3"},""" +
                """{"sessionId":"s2","trackName":"video-u2","errorCode":"not_found","errorDescription":"x"}],""" +
                """"sessionDescription":{"type":"offer","sdp":"v=0 sfu offer"}}"""

            val pulled =
                assertIs<Resource.Success<PulledTracks>>(
                    media.pullTracks("s1", listOf(RemoteTrack("s2", "audio-u2"), RemoteTrack("s2", "video-u2"))),
                ).data

            assertEquals("v=0 sfu offer", pulled.offerSdp)
            assertTrue(pulled.requiresRenegotiation)
            assertEquals("3", pulled.tracks.first { it.trackName == "audio-u2" }.mid)
            assertNull(pulled.tracks.first { it.trackName == "video-u2" }.mid, "a track the SFU couldn't pull")
            val sent = reader.decodeFromString<TracksSent>(clients.last.text())
            assertNull(sent.sessionDescription, "a pull sends no session description")
            assertEquals(Track("remote", null, "s2", "audio-u2"), sent.tracks.first())
        }

    @Test
    fun `a pulled track without a session id in the answer is matched to the one asked for by name`() =
        runBlocking<Unit> {
            reply =
                """{"requiresImmediateRenegotiation":true,""" +
                """"sessionDescription":{"type":"offer","sdp":"v=0 sfu offer"},""" +
                """"tracks":[{"trackName":"audio-u2","mid":"2"},{"trackName":"video-u2","mid":"3"}]}"""
            val pulled =
                assertIs<Resource.Success<PulledTracks>>(
                    media.pullTracks("s1", listOf(RemoteTrack("s2", "audio-u2"), RemoteTrack("s2", "video-u2"))),
                ).data
            assertEquals(listOf("s2", "s2"), pulled.tracks.map { it.sessionId })
            assertEquals("3", pulled.tracks.first { it.trackName == "video-u2" }.mid)
        }

    @Test
    fun `renegotiate puts the answer and close forces the tracks shut`() =
        runBlocking<Unit> {
            reply = "{}"
            assertIs<Resource.Success<Unit>>(media.renegotiate("s1", "v=0 answer"))
            assertEquals(HttpMethod.Put, clients.last.method)
            assertEquals(
                "https://rtc.live.cloudflare.com/v1/apps/app1/sessions/s1/renegotiate",
                clients.last.url.toString(),
            )

            reply = """{"requiresImmediateRenegotiation":false,"tracks":[{"mid":"0"},{"mid":"1"}]}"""
            assertIs<Resource.Success<Unit>>(media.closeTracks("s1", listOf("0", "1")))
            assertEquals(
                "https://rtc.live.cloudflare.com/v1/apps/app1/sessions/s1/tracks/close",
                clients.last.url.toString(),
            )
            assertEquals(
                CloseSent(listOf(Track(mid = "0"), Track(mid = "1")), true),
                reader.decodeFromString(clients.last.text()),
            )
        }

    @Test
    fun `our refused secret, a busy SFU and a network failure are unavailable, and a bad offer is rejected`() =
        runBlocking<Unit> {
            for (code in listOf(401, 403, 429, 503)) {
                status = code
                assertIs<IntegrationError.Unavailable>(assertIs<Resource.Error>(media.newSession()).error)
            }
            status = 400
            reply = """{"errorCode":"invalid_request_body","errorDescription":"bad sdp"}"""
            assertIs<IntegrationError.Rejected>(
                assertIs<Resource.Error>(media.pushTracks("s1", "nonsense", emptyList())).error,
            )
            status = 200
            assertIs<IntegrationError.Rejected>(
                assertIs<Resource.Error>(media.pushTracks("s1", "nonsense", emptyList())).error,
                "an error in a 200 answer is still an error",
            )
            down = true
            assertIs<IntegrationError.Unavailable>(assertIs<Resource.Error>(media.newSession()).error)
        }

    @Test
    fun `ice servers are STUN alone without a TURN key, and add TURN credentials without port 53 when there is one`() =
        runBlocking<Unit> {
            assertEquals(listOf("stun:stun.cloudflare.com:3478"), media.iceServers().single().urls)

            reply =
                """{"iceServers":[{"urls":["stun:stun.cloudflare.com:3478","stun:stun.cloudflare.com:53"]},""" +
                """{"urls":["turn:turn.cloudflare.com:3478?transport=udp",""" +
                """"turn:turn.cloudflare.com:53?transport=udp",""" +
                """"turns:turn.cloudflare.com:5349?transport=tcp"],"username":"u","credential":"c"}]}"""
            val withTurn = CloudflareRealtime("app1", "app-secret", http, TurnKey("key1", "turn-secret"))

            val servers = withTurn.iceServers()

            assertEquals(2, servers.size)
            val turn = servers.last()
            assertEquals(
                listOf("turn:turn.cloudflare.com:3478?transport=udp", "turns:turn.cloudflare.com:5349?transport=tcp"),
                turn.urls,
            )
            assertEquals("u", turn.username)
            assertEquals("c", turn.credential)
            val request = clients.last
            assertEquals(
                "https://rtc.live.cloudflare.com/v1/turn/keys/key1/credentials/generate-ice-servers",
                request.url.toString(),
            )
            assertEquals("Bearer turn-secret", request.headers[HttpHeaders.Authorization])
            val asked = clients.requests.size
            withTurn.iceServers()
            assertEquals(asked, clients.requests.size, "credentials are reused until they near their expiry")
        }

    @Test
    fun `closing a session that is already gone (410, 404) is done, not refused`() =
        runBlocking<Unit> {
            reply = """{"errorCode":"session_gone"}"""
            for (code in listOf(410, 404)) {
                status = code
                assertIs<Resource.Success<Unit>>(media.closeTracks("s1", listOf("0")))
            }
            status = 400
            assertIs<IntegrationError.Rejected>(assertIs<Resource.Error>(media.closeTracks("s1", listOf("0"))).error)
            status = 410
            assertIs<IntegrationError.Rejected>(
                assertIs<Resource.Error>(media.pushTracks("s1", "v=0", emptyList())).error,
                "only a close takes 410 as done",
            )
        }
}
