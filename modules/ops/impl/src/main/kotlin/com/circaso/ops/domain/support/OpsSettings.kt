package com.circaso.ops.domain.support

/**
 * The ops module's settings (`OPS_*`). [bootstrapAdmins] are the emails from `OPS_BOOTSTRAP_ADMINS` (lowercased): always
 * active ADMINs, so a fresh install has someone who can add the rest, and nobody can lock the platform out of its own
 * admin from the portal. Pages default to [pageDefault] rows and never exceed [pageMax] (the API conventions).
 */
data class OpsSettings(
    val bootstrapAdmins: Set<String> = emptySet(),
    val pageDefault: Int = 50,
    val pageMax: Int = 200,
    /** The longest dashboard or finance range, in days. */
    val maxRangeDays: Int = 366,
)
