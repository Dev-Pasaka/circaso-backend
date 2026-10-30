package com.circaso.integrations.api

import com.circaso.shared.Resource

/**
 * The media server behind calls (an SFU): each participant's phone holds one WebRTC connection to it, pushes its own
 * tracks and pulls the others'. The server only brokers the session descriptions; media never passes through
 * Circaso. Every failure is an [IntegrationError]; no SDP, token or session id is ever logged.
 */
interface CallMedia {
    /** Opens a session for one participant's connection; its id. */
    suspend fun newSession(): Resource<String>

    /** Publishes the phone's local [tracks] described by [offerSdp]; the SFU's answer. */
    suspend fun pushTracks(
        sessionId: String,
        offerSdp: String,
        tracks: List<LocalTrack>,
    ): Resource<PushedTracks>

    /** Subscribes [sessionId] to other sessions' tracks. When [PulledTracks.requiresRenegotiation], answer the offer. */
    suspend fun pullTracks(sessionId: String, tracks: List<RemoteTrack>): Resource<PulledTracks>

    /** The phone's answer to an offer the SFU made in [pullTracks]. */
    suspend fun renegotiate(sessionId: String, answerSdp: String): Resource<Unit>

    /** Stops the tracks at [mids] on the session, without a new negotiation (the participant is leaving). */
    suspend fun closeTracks(sessionId: String, mids: List<String>): Resource<Unit>

    /** STUN, and TURN with short-lived credentials when configured, for the phone's peer connection. */
    suspend fun iceServers(): List<IceServer>
}

/** A track the phone publishes: its transceiver's [mid] in the offer and the name others pull it by. */
data class LocalTrack(val mid: String, val trackName: String)

/** A track published by another session, to pull. */
data class RemoteTrack(val sessionId: String, val trackName: String)

data class PushedTracks(val answerSdp: String)

/** The SFU's offer to receive the pulled tracks (null when nothing needs negotiating), and where each landed. */
data class PulledTracks(val offerSdp: String?, val requiresRenegotiation: Boolean, val tracks: List<PulledTrack>)

/** One pulled track; [mid] is null when the SFU could not pull it. */
data class PulledTrack(val trackName: String, val sessionId: String, val mid: String?)

data class IceServer(val urls: List<String>, val username: String? = null, val credential: String? = null)
