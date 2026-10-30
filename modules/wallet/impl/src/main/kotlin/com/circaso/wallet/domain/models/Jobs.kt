package com.circaso.wallet.domain.models

import java.time.Duration

/**
 * How the wallet's scheduled jobs (Wallet → Data → Scheduled jobs) behave, from `WALLET_JOBS_*` configuration. They
 * are the safety net for a lost Circle webhook: each applies what Circle says through the same path the webhook takes.
 */
data class JobSettings(
    /** `wallet.stuck-transfers` runs this often (the doc's 5 minutes; 1 minute on the sandbox). */
    val stuckInterval: Duration = Duration.ofMinutes(1),
    /** A `SUBMITTED` transfer at least this old is read from Circle. */
    val stuckAfter: Duration = Duration.ofSeconds(30),
    /** Still `SUBMITTED` this long after it was prepared: warn operators (once per transfer). */
    val stuckAlertAfter: Duration = Duration.ofHours(1),
    /** Older than this, a `SUBMITTED` transfer is left to the daily reconciliation. */
    val stuckMaxAge: Duration = Duration.ofHours(24),
    /** The most transfers one run of a transfer job looks at. */
    val transferBatch: Int = 50,
    /** `wallet.reconcile-active` runs this often. */
    val reconcileInterval: Duration = Duration.ofMinutes(2),
    /** A user active (a W1 read, a W3 session, a payment) within this window is reconciled by the light pass. */
    val activeWindow: Duration = Duration.ofMinutes(30),
    /** The light pass asks Circle for transactions created within this window. */
    val activeLookback: Duration = Duration.ofHours(1),
    /** The most users one light pass reconciles. */
    val activeBatch: Int = 50,
    /** The most users the daily pass reconciles. */
    val dailyBatch: Int = 1_000,
    /** The daily pass asks Circle for transactions created within this window (a day, with room for drift). */
    val dailyLookback: Duration = Duration.ofHours(26),
    /** The most transactions read from Circle per user in one pass. */
    val transactionsPerUser: Int = 100,
)

/** Which reconciliation pass runs: the frequent one over active users, or the daily one over the day's activity. */
enum class ReconcilePass { ACTIVE, DAILY }
