package com.circaso.share.domain.support

import java.time.Duration

/**
 * Share's settings (`SHARE_*`, `.env.example` → share links). [baseUrl] is the origin a link is made on, without a
 * trailing slash; a link is `<baseUrl>/s/<code>`.
 */
data class ShareConfig(
    val baseUrl: String = "http://localhost:8080",
    val androidPackage: String = "com.circaso.app",
    val androidCertFingerprints: List<String> = emptyList(),
    val iosAppIds: List<String> = listOf("985W5Z7984.com.circaso.app"),
    val androidStoreUrl: String = "https://play.google.com/store/apps/details?id=com.circaso.app",
    val iosStoreUrl: String = "https://circaso.com",
    val websiteUrl: String = "https://circaso.com",
    val previewImageUrl: String = "https://assets.circaso.com/brand/circaso-icon-1024.png",
    val viewsPerMinute: Long = 60,
    /** How long a revoked or ended link's row stays, so its page says it was turned off. */
    val tombstone: Duration = Duration.ofDays(30),
    val maxDays: Int = 365,
    val pageDefault: Int = 50,
    val pageMax: Int = 200,
) {
    fun urlOf(code: String): String = "$baseUrl/s/$code"

    /** The host links are made on (`sandbox.circaso.com`), for the Android intent that opens the app. */
    val host: String get() = baseUrl.substringAfter("://").substringBefore('/')
}
