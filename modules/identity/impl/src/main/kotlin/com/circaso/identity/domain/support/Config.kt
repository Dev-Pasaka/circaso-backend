package com.circaso.identity.domain.support

import com.circaso.identity.domain.ports.AttestationMode
import java.time.Duration

/** Identity's settings, read from the `identity` config section by `di`. */
data class IdentityConfig(
    val attestation: AttestationMode = AttestationMode.LOG,
    /** The app's SMS Retriever hash, appended to the code text so Android reads it automatically. */
    val smsRetrieverHash: String? = null,
    val codeLength: Int = 6,
    val codeTtl: Duration = Duration.ofMinutes(5),
    val resendAfter: Duration = Duration.ofSeconds(30),
    val maxCodeAttempts: Int = 5,
    val proofWindow: Duration = Duration.ofMinutes(5),
    /** TODO(decision: how long a deleted account stays recoverable before AccountDeleted is published) */
    val deletionGrace: Duration = Duration.ofDays(30),
    val phoneReleaseNotice: Duration = Duration.ofDays(7),
)
