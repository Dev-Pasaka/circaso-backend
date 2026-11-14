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
    /**
     * The ciphertext of one file: 1 B to this. Decision 2026-11-14 (was 32 MiB, 2026-11-03): 64 MiB, so a minute of
     * 1080p video keeps close to the bit rate it deserves (about 7.5 Mbit/s, was about 3.7) and most short clips go
     * as they were shot. Set by `STATUS_MEDIA_MAX_BYTES`. An older app still sends at most 32 MiB.
     */
    val mediaMaxBytes: Long = MEDIA_MAX_BYTES,
    /**
     * How long ST1's upload link works. Storage checks it when the PUT starts, and the phone retries a dropped upload
     * with the same link, so 64 MiB on a slow connection and its retries need longer than a download link (as chat
     * attachments, 2026-11-07).
     */
    val mediaUploadUrlTtl: Duration = Duration.ofHours(1),
    /** How long ST8's download link works (the phone asks for a fresh one for each download). */
    val mediaUrlTtl: Duration = Duration.ofMinutes(15),
    /**
     * A file prepared but not attached to a moment within this is deleted. Longer than [mediaUploadUrlTtl], so a file
     * whose upload began near the end of its link is still there when the moment is posted.
     */
    val unattachedMediaTtl: Duration = Duration.ofHours(2),
    val mediaPurgeBatch: Int = 200,
    /** `POST /moments/keys/request`: posters named in one call, and how often one requester may ask one poster. */
    val keyRequestPostersMax: Int = 100,
    val keyRequestInterval: Duration = Duration.ofMinutes(10),
    /**
     * `POST /moments/key-copies`: copies in one call (one per live moment per person; a poster has at most 50 live
     * moments), and one copy decoded: a moment's body sealed pairwise, so the body's limit plus Signal's overhead.
     */
    val keyCopiesMax: Int = 200,
    val resealedCopyMaxBytes: Int = 72 * 1_024,
    /** ST4's page: default and most. */
    val pageDefault: Int = 50,
    val pageMax: Int = 200,
)

/** `STATUS_MEDIA_MAX_BYTES` when unset: 64 MiB of ciphertext (decision 2026-11-14). */
const val MEDIA_MAX_BYTES: Long = 64L * 1_024 * 1_024
