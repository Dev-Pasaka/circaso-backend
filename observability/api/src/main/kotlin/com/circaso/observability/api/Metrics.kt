package com.circaso.observability.api

/**
 * The metrics catalog (Architecture → Monitoring and observability → Metrics catalog), by Prometheus name.
 * A metric ending in `_seconds` is a duration in seconds. Modules add their own, prefixed `circaso_<module>_`.
 */
object Metrics {
    const val AUTH_SIGNINS = "circaso_auth_signins_total" // provider, next
    const val OTP_SENT = "circaso_otp_sent_total" // channel, result
    const val OTP_VERIFIED = "circaso_otp_verified_total" // channel, result
    const val SMS_COST_KES = "circaso_sms_cost_kes_total" // provider
    const val INTEGRATION_DURATION = "circaso_integration_duration_seconds" // vendor, operation, outcome
    const val INTEGRATION_CIRCUIT_OPEN = "circaso_integration_circuit_open" // vendor (gauge, 1 = open)
    const val EVENTS_PUBLISHED = "circaso_events_published_total" // stream, type
    const val EVENTS_HANDLED_DURATION = "circaso_events_handled_duration_seconds" // group, type, outcome
    const val EVENTS_LAG = "circaso_events_lag" // group (gauge)
    const val EVENTS_DLQ = "circaso_events_dlq" // stream (gauge)
    const val OUTBOX_UNPUBLISHED = "circaso_outbox_unpublished" // module (gauge)

    /** r2dbc-pool's own counts per pool: state is acquired, idle, allocated, pending or max. */
    const val DB_POOL_CONNECTIONS = "circaso_db_pool_connections" // pool, state (gauge)
    const val CONTACTS_SYNC_MATCHES_RATIO = "circaso_contacts_sync_matches_ratio"
    const val GATEWAY_SOCKET_FRAMES_DROPPED = "circaso_gateway_socket_frames_dropped_total" // reason

    /** Live voice: from the end of the user's speech to the first of Asha's audio sent to the phone, per turn. */
    const val ASSISTANT_LIVE_FIRST_AUDIO = "circaso_assistant_live_first_audio_seconds"

    /** Label keys that would leak personal data or explode the series count; the implementation rejects them. */
    val forbiddenLabels: Set<String> = setOf("userId", "user", "phone", "handle", "email", "ip", "token", "deviceId")
}
