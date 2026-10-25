package com.circaso.messaging.domain.support

import java.time.Duration

/** Messaging's settings. The limits are the spec's (Messaging → Endpoints, Data → Redis). */
data class MessagingConfig(
    /** How far a key upload's `X-Device-Timestamp` may be from the server clock: identity's window for its proofs. */
    val proofWindow: Duration = Duration.ofMinutes(5),
    val maxOneTimePreKeys: Int = 100,
    val bundlesPerHour: Int = 60,
)
