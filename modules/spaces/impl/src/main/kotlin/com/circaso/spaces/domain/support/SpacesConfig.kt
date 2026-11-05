package com.circaso.spaces.domain.support

import java.time.Duration

/** Spaces' numbers (Spaces API; decisions 2026-11-04). In code until one needs to change without a release. */
data class SpacesConfig(
    val nameMax: Int = 60,
    val aboutMax: Int = 300,
    val teamMax: Int = 20,
    /** Accounts younger than this can't start a space (SN7c). */
    val accountAgeToCreate: Duration = Duration.ofDays(7),
    val createsPerWindow: Int = 3,
    val createWindow: Duration = Duration.ofHours(24),
    /** Price bounds, USDC minor units (6 decimals): 0.10 to 10,000. */
    val priceMinMinor: Long = 100_000,
    val priceMaxMinor: Long = 10_000_000_000,
    /** A membership payment (SP13) is held as long as the wallet's signing window. */
    val paymentLifetime: Duration = Duration.ofMinutes(15),
    val graceAfterPeriod: Duration = Duration.ofDays(3),
    val remindMonthlyBefore: Duration = Duration.ofDays(3),
    val remindYearlyBefore: Duration = Duration.ofDays(7),
    val requestRetryAfter: Duration = Duration.ofDays(30),
    val inviteLifetime: Duration = Duration.ofDays(14),
    val invitesPerCall: Int = 50,
    /** SP16 shows invites sent in this window. */
    val inviteHistory: Duration = Duration.ofDays(90),
    val postTextMax: Int = 4_000,
    val mediaPerPost: Int = 10,
    val postsPerMinute: Int = 20,
    val postsPerDay: Int = 500,
    /** Visitors of a public space read only this many of its latest posts (SS1). */
    val visitorPosts: Int = 20,
    val reportDetailMax: Int = 500,
    val reportsPerDay: Int = 20,
    val pageDefault: Int = 50,
    val pageMax: Int = 200,
    val suggestedDefault: Int = 20,
    val unreadCap: Int = 99,
    val mediaUploadWindow: Duration = Duration.ofHours(1),
    val mediaUrlLifetime: Duration = Duration.ofHours(1),
    val imageMaxBytes: Long = 10L * 1024 * 1024,
    val videoMaxBytes: Long = 64L * 1024 * 1024,
    val voiceMaxBytes: Long = 16L * 1024 * 1024,
)
