package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.CallMedia
import com.circaso.integrations.api.IceServer
import com.circaso.integrations.api.LocalTrack
import com.circaso.integrations.api.PulledTrack
import com.circaso.integrations.api.PulledTracks
import com.circaso.integrations.api.PushedTracks
import com.circaso.integrations.api.RemoteTrack
import com.circaso.shared.Resource
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tests and local runs without Cloudflare: made-up session ids and SDP that only looks like an answer or an offer
 * (it carries the session id, so a test can tell whose it is). It remembers each session's tracks, so a pull of a
 * track nobody pushed comes back without a `mid`, as the SFU would. No media ever flows.
 */
open class FakeCallMedia : CallMedia {
    /** Each session's published track names, with the mid they were offered on. */
    val published: MutableMap<String, MutableMap<String, String>> = ConcurrentHashMap()
    val closed: MutableList<Pair<String, List<String>>> = CopyOnWriteArrayList()
    val renegotiated: MutableList<String> = CopyOnWriteArrayList()
    private val mids = AtomicInteger(100)

    override suspend fun newSession(): Resource<String> {
        val id = "fake-" + UUID.randomUUID().toString().replace("-", "")
        published[id] = ConcurrentHashMap()
        return Resource.success(id)
    }

    override suspend fun pushTracks(
        sessionId: String,
        offerSdp: String,
        tracks: List<LocalTrack>,
    ): Resource<PushedTracks> {
        val session = published.getOrPut(sessionId) { ConcurrentHashMap() }
        tracks.forEach { session[it.trackName] = it.mid }
        return Resource.success(PushedTracks(sdp("answer", sessionId)))
    }

    override suspend fun pullTracks(sessionId: String, tracks: List<RemoteTrack>): Resource<PulledTracks> {
        val pulled =
            tracks.map { track ->
                val exists = published[track.sessionId]?.containsKey(track.trackName) == true
                val mid = if (exists) mids.incrementAndGet().toString() else null
                PulledTrack(track.trackName, track.sessionId, mid)
            }
        val needsOffer = pulled.any { it.mid != null }
        return Resource.success(
            PulledTracks(
                offerSdp = if (needsOffer) sdp("offer", sessionId) else null,
                requiresRenegotiation = needsOffer,
                tracks = pulled,
            ),
        )
    }

    override suspend fun renegotiate(sessionId: String, answerSdp: String): Resource<Unit> {
        renegotiated += sessionId
        return Resource.success(Unit)
    }

    override suspend fun closeTracks(sessionId: String, mids: List<String>): Resource<Unit> {
        closed += sessionId to mids
        return Resource.success(Unit)
    }

    override suspend fun iceServers(): List<IceServer> = listOf(IceServer(listOf("stun:stun.cloudflare.com:3478")))

    private fun sdp(type: String, sessionId: String): String =
        "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=fake-sfu-$type $sessionId\r\nt=0 0\r\n"
}

/** Local runs: the fake, with one line per step so a developer sees the call flow in the console. */
class LogCallMedia : FakeCallMedia() {
    private val log = LoggerFactory.getLogger(LogCallMedia::class.java)

    override suspend fun newSession(): Resource<String> =
        super.newSession().also { log.info("call media: new session") }

    override suspend fun pushTracks(
        sessionId: String,
        offerSdp: String,
        tracks: List<LocalTrack>,
    ): Resource<PushedTracks> {
        log.info("call media: push {} tracks", tracks.size)
        return super.pushTracks(sessionId, offerSdp, tracks)
    }

    override suspend fun pullTracks(sessionId: String, tracks: List<RemoteTrack>): Resource<PulledTracks> {
        log.info("call media: pull {} tracks", tracks.size)
        return super.pullTracks(sessionId, tracks)
    }

    override suspend fun closeTracks(sessionId: String, mids: List<String>): Resource<Unit> {
        log.info("call media: close {} tracks", mids.size)
        return super.closeTracks(sessionId, mids)
    }
}
