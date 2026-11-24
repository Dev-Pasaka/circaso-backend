package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.usecases.GetRampMerchantSecurity
import com.circaso.ops.domain.usecases.GetRampSecuritySettings
import com.circaso.ops.domain.usecases.UnlockRampMerchantWithdrawals
import com.circaso.ops.domain.usecases.UpdateRampSecuritySettings
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsMerchantSecurity
import com.circaso.ramp.api.OpsSecuritySettings
import com.circaso.shared.Resource

/** Staff's view of ramp merchant security (2026-11-24). */
class RampSecurityService(
    private val getRampMerchantSecurity: GetRampMerchantSecurity,
    private val unlockRampMerchantWithdrawals: UnlockRampMerchantWithdrawals,
    private val getRampSecuritySettings: GetRampSecuritySettings,
    private val updateRampSecuritySettings: UpdateRampSecuritySettings,
) {
    suspend fun merchant(merchantId: MerchantId): Resource<OpsMerchantSecurity> = getRampMerchantSecurity(merchantId)

    suspend fun unlock(
        actor: Actor,
        merchantId: MerchantId,
        reason: String,
    ): Resource<OpsMerchantSecurity> = unlockRampMerchantWithdrawals(actor, merchantId, reason)

    suspend fun settings(): Resource<OpsSecuritySettings> = getRampSecuritySettings()

    suspend fun updateSettings(
        actor: Actor,
        passkeyRequired: Boolean,
        version: Long,
        reason: String,
    ): Resource<OpsSecuritySettings> = updateRampSecuritySettings(actor, passkeyRequired, version, reason)
}
