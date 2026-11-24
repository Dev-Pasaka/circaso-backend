package com.circaso.ramp.domain.repositories

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.MerchantAlert
import com.circaso.ramp.domain.models.MerchantSecurity
import com.circaso.ramp.domain.models.Passkey
import com.circaso.ramp.domain.models.SecuritySettings
import java.time.Instant
import java.util.UUID

/** `ramp.merchant_passkeys`. */
interface PasskeyRepository {
    /** False when that credential is already registered. */
    suspend fun insert(passkey: Passkey): Boolean

    suspend fun find(passkeyId: UUID): Passkey?

    suspend fun byCredentialId(credentialId: ByteArray): Passkey?

    /** Oldest first. */
    suspend fun ofMerchant(merchantId: MerchantId): List<Passkey>

    suspend fun count(merchantId: MerchantId): Int

    /** Records a use: the authenticator's new counter. */
    suspend fun used(
        passkeyId: UUID,
        signCount: Long,
        backedUp: Boolean,
        at: Instant,
    )

    suspend fun delete(passkeyId: UUID): Boolean
}

/** `ramp.merchant_security`: a merchant with no row has the defaults. */
interface MerchantSecurityRepository {
    suspend fun find(merchantId: MerchantId): MerchantSecurity

    suspend fun setChoices(
        merchantId: MerchantId,
        orderEmails: Boolean,
        dailySummary: Boolean,
        at: Instant,
    )

    /** Locks withdrawals; false when they already were. */
    suspend fun lock(
        merchantId: MerchantId,
        alertId: UUID?,
        cancelled: Int,
        at: Instant,
    ): Boolean

    /** Lifts a lock; false when there was none. */
    suspend fun unlock(
        merchantId: MerchantId,
        by: String,
        reason: String,
        at: Instant,
    ): Boolean

    /** Merchants who asked for the daily summary. */
    suspend fun withDailySummary(): List<MerchantId>
}

/** `ramp.merchant_alerts`: written with the event, sent by the job. */
interface MerchantAlertRepository {
    /** False when an alert with the same dedupe key exists: that event was already written. */
    suspend fun insert(alert: MerchantAlert): Boolean

    suspend fun find(alertId: UUID): MerchantAlert?

    /** Pending alerts due by [now], oldest first. */
    suspend fun due(now: Instant, limit: Int): List<MerchantAlert>

    suspend fun sent(alertId: UUID, at: Instant)

    suspend fun skipped(
        alertId: UUID,
        why: String,
        at: Instant,
    )

    suspend fun retry(
        alertId: UUID,
        attempts: Int,
        next: Instant,
        why: String,
    )

    suspend fun failed(
        alertId: UUID,
        attempts: Int,
        why: String,
    )

    /** Marks the alert's lock link used; true for the one call that did. */
    suspend fun useLink(alertId: UUID, at: Instant): Boolean

    suspend fun recent(merchantId: MerchantId, limit: Int): List<MerchantAlert>
}

/** `ramp.security_settings`: one row, seeded with the defaults. */
interface SecuritySettingsRepository {
    suspend fun get(): SecuritySettings

    /** Replaces the settings when [version] is the one in force; false otherwise. */
    suspend fun update(
        settings: SecuritySettings,
        version: Long,
        at: Instant,
    ): Boolean
}
