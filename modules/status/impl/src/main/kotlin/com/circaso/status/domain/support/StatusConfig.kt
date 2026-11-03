package com.circaso.status.domain.support

import java.time.Duration

/** The Moments API's numbers (Moments API → Endpoints, Retention), in one place for the use cases and their tests. */
data class StatusConfig(
    /** How long a moment lives from `postedAt`. */
    val momentLifetime: Duration = Duration.ofHours(24),
    val audienceMax: Int = 1_024,
    /** The encrypted body, decoded from base64. */
    val ciphertextMaxBytes: Int = 64 * 1_024,
    /** One pairwise-encrypted sender-key distribution message, decoded; a real one is well under 1 KiB. */
    val keyCopyMaxBytes: Int = 16 * 1_024,
    val mediaPerMomentMax: Int = 1,
    /** Moments one person may post in [rateWindow]. */
    val momentsPerWindow: Int = 50,
    val rateWindow: Duration = Duration.ofHours(24),
    /** The ciphertext of one file: 1 B to 32 MiB. */
    val mediaMaxBytes: Long = 32L * 1_024 * 1_024,
    /** How long an upload or download link works. */
    val mediaUrlTtl: Duration = Duration.ofMinutes(15),
    /** A file prepared but not attached to a moment within this is deleted. */
    val unattachedMediaTtl: Duration = Duration.ofHours(1),
    val mediaPurgeBatch: Int = 200,
    /** ST4's page: default and most. */
    val pageDefault: Int = 50,
    val pageMax: Int = 200,
)
