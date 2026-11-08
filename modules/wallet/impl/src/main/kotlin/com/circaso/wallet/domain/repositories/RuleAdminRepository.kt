package com.circaso.wallet.domain.repositories

import com.circaso.wallet.domain.models.FeeBounds
import com.circaso.wallet.domain.models.FeeRule
import com.circaso.wallet.domain.models.LimitRule
import com.circaso.wallet.domain.models.VersionWrite
import java.time.Instant
import java.util.UUID

/**
 * Staff writes to `fee_rules`, `fee_bounds` and `limit_rules` (decision 2026-11-08). Called inside the use case's
 * transaction: the locks hold until it commits, and each read after a lock sees what the writer before committed.
 */
interface RuleAdminRepository {
    suspend fun feeBounds(): FeeBounds

    /**
     * Locks the bounds row (`FOR UPDATE`) and returns it. Every fee write takes this lock first, so fee writes and a
     * change of the bounds run one after the other and a rate is checked against bounds that can't move underneath.
     */
    suspend fun lockFeeBounds(): FeeBounds

    suspend fun feeVersions(ruleId: UUID): List<FeeRule>

    /** Writes a new version. `TAKEN` when (rule, version) exists; `OUT_OF_BOUNDS` when the bounds trigger refused it. */
    suspend fun insertFeeVersion(rule: FeeRule): VersionWrite

    /** Sets [version]'s `effective_to`; false when there is no such version. */
    suspend fun endFeeVersion(
        ruleId: UUID,
        version: Int,
        at: Instant,
    ): Boolean

    suspend fun updateFeeBounds(
        minRateBps: Int,
        maxRateBps: Int,
        at: Instant,
    ): FeeBounds

    /** Locks every version of [ruleId] (`FOR UPDATE`); false when the rule has none. Read the versions after it. */
    suspend fun lockLimitRule(ruleId: UUID): Boolean

    suspend fun limitVersions(ruleId: UUID): List<LimitRule>

    /** `TAKEN` when (rule, version) exists. */
    suspend fun insertLimitVersion(rule: LimitRule): VersionWrite

    suspend fun endLimitVersion(
        ruleId: UUID,
        version: Int,
        at: Instant,
    ): Boolean
}
