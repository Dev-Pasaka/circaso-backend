package com.circaso.ramp.api

import com.circaso.shared.Resource
import java.time.Instant
import java.util.UUID

/**
 * Merchant security for staff (2026-11-24), through the ops gateway: a merchant's withdrawals lock ("This wasn't me"),
 * their passkeys and recent security and activity emails, lifting a lock (audited by ops), and the one security
 * setting. Errors: `ramp.merchant_not_found` (404), `ramp.not_locked` (409), `ramp.settings_changed` (409).
 */
interface RampSecurityAdmin {
    suspend fun merchantSecurity(merchantId: MerchantId): Resource<OpsMerchantSecurity>

    /** Lifts the lock. Addresses frozen by it stay frozen: the merchant removes them and adds them again. */
    suspend fun unlockWithdrawals(
        merchantId: MerchantId,
        by: UUID,
        reason: String,
    ): Resource<OpsMerchantSecurity>

    suspend fun securitySettings(): Resource<OpsSecuritySettings>

    suspend fun updateSecuritySettings(
        merchantPasskeyRequired: Boolean,
        version: Long,
        by: UUID,
        reason: String,
    ): Resource<OpsSecuritySettings>
}

/** [lockedAt] null: not locked. [lockedByLink]: the merchant's own link locked it (else staff). */
data class OpsMerchantSecurity(
    val merchantId: MerchantId,
    val lockedAt: Instant?,
    val lockedByLink: Boolean,
    val lockedCancelled: Int,
    val unlockedAt: Instant?,
    val unlockedBy: String?,
    val unlockReason: String?,
    val orderEmails: Boolean,
    val dailySummary: Boolean,
    val passkeys: List<OpsMerchantPasskey>,
    val alerts: List<OpsMerchantAlert>,
)

data class OpsMerchantPasskey(
    val passkeyId: UUID,
    val label: String,
    val createdAt: Instant,
    val lastUsedAt: Instant?,
    val backedUp: Boolean,
)

/** One email to the merchant: `kind`, `state` (`PENDING`, `SENT`, `SKIPPED`, `FAILED`) and why, never its content. */
data class OpsMerchantAlert(
    val alertId: UUID,
    val kind: String,
    val security: Boolean,
    val state: String,
    val failure: String?,
    val createdAt: Instant,
    val sentAt: Instant?,
    val linkUsedAt: Instant?,
)

data class OpsSecuritySettings(
    val merchantPasskeyRequired: Boolean,
    val version: Long,
    val updatedBy: String?,
    val reason: String?,
    val updatedAt: Instant?,
)
