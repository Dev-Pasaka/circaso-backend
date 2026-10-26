package com.circaso.messaging.domain.support

import java.time.Duration

/** Messaging's settings. The limits are the spec's (Messaging → Endpoints, Data → Redis) and decisions'. */
data class MessagingConfig(
    /** How far a key upload's `X-Device-Timestamp` may be from the server clock: identity's window for its proofs. */
    val proofWindow: Duration = Duration.ofMinutes(5),
    val maxOneTimePreKeys: Int = 100,
    val bundlesPerHour: Int = 60,
    /** Decision 2026-10-25: the creator and everyone added count. */
    val maxGroupMembers: Int = 1_000,
    /** A group's sealed name and avatar, decoded (Messaging → Error codes, `messaging.group_invalid`). */
    val maxSealedMetaBytes: Int = 4 * 1_024,
    /** How often a membership change re-reads the chat when another change won the race for its version. */
    val chatWriteAttempts: Int = 5,
)
