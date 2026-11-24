package com.circaso.ops

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchantSecurity
import com.circaso.ramp.api.OpsSecuritySettings
import com.circaso.ramp.api.RampSecurityAdmin
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private data class SecurityRefusal(override val code: String, override val kind: ErrorKind) : DomainError {
    override val message: String = code
}

/** The ramp's merchant security contract in memory (2026-11-24): one known merchant, lockable. */
class FakeRampSecurityAdmin : RampSecurityAdmin {
    val known = ConcurrentHashMap<MerchantId, OpsMerchantSecurity>()
    var settings = OpsSecuritySettings(false, 1, null, null, null)
    val unlocks = mutableListOf<Pair<MerchantId, String>>()

    fun lockedMerchant(): MerchantId {
        val id = MerchantId.new()
        known[id] =
            OpsMerchantSecurity(id, AT, true, 2, null, null, null, true, false, emptyList(), emptyList())
        return id
    }

    override suspend fun merchantSecurity(merchantId: MerchantId): Resource<OpsMerchantSecurity> =
        known[merchantId]?.let { Resource.success(it) }
            ?: Resource.Error(SecurityRefusal("ramp.merchant_not_found", ErrorKind.NOT_FOUND))

    override suspend fun unlockWithdrawals(
        merchantId: MerchantId,
        by: UUID,
        reason: String,
    ): Resource<OpsMerchantSecurity> {
        val row =
            known[merchantId] ?: return Resource.Error(SecurityRefusal("ramp.merchant_not_found", ErrorKind.NOT_FOUND))
        if (row.lockedAt == null) return Resource.Error(SecurityRefusal("ramp.not_locked", ErrorKind.CONFLICT))
        val after = row.copy(lockedAt = null, unlockedAt = AT, unlockedBy = by.toString(), unlockReason = reason)
        known[merchantId] = after
        unlocks += merchantId to reason
        return Resource.success(after)
    }

    override suspend fun securitySettings(): Resource<OpsSecuritySettings> = Resource.success(settings)

    override suspend fun updateSecuritySettings(
        merchantPasskeyRequired: Boolean,
        version: Long,
        by: UUID,
        reason: String,
    ): Resource<OpsSecuritySettings> {
        if (version !=
            settings.version
        ) {
            return Resource.Error(SecurityRefusal("ramp.settings_changed", ErrorKind.CONFLICT))
        }
        settings = OpsSecuritySettings(merchantPasskeyRequired, version + 1, by.toString(), reason, AT)
        return Resource.success(settings)
    }

    private companion object {
        val AT: Instant = Instant.parse("2026-11-24T09:00:00Z")
    }
}
