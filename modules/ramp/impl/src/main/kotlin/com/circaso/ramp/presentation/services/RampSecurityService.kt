package com.circaso.ramp.presentation.services

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.SecuritySettings
import com.circaso.ramp.domain.models.StaffSecurity
import com.circaso.ramp.domain.usecases.GetMerchantSecurity
import com.circaso.ramp.domain.usecases.GetSecuritySettings
import com.circaso.ramp.domain.usecases.QueueDailySummaries
import com.circaso.ramp.domain.usecases.SendMerchantAlerts
import com.circaso.ramp.domain.usecases.UnlockWithdrawals
import com.circaso.ramp.domain.usecases.UpdateSecuritySettings
import com.circaso.shared.Resource

/** Staff's merchant security (2026-11-24), and the alert jobs. One use case per method. */
class RampSecurityService(
    private val getMerchantSecurity: GetMerchantSecurity,
    private val unlockWithdrawals: UnlockWithdrawals,
    private val getSecuritySettings: GetSecuritySettings,
    private val updateSecuritySettings: UpdateSecuritySettings,
    private val sendMerchantAlerts: SendMerchantAlerts,
    private val queueDailySummaries: QueueDailySummaries,
) {
    suspend fun merchant(merchantId: MerchantId): Resource<StaffSecurity> = getMerchantSecurity(merchantId)

    suspend fun unlock(
        merchantId: MerchantId,
        by: String,
        reason: String,
    ): Resource<Unit> = unlockWithdrawals(merchantId, by, reason)

    suspend fun settings(): Resource<SecuritySettings> = getSecuritySettings()

    suspend fun updateSettings(
        required: Boolean,
        version: Long,
        by: String,
        reason: String,
    ): Resource<SecuritySettings> = updateSecuritySettings(required, version, by, reason)

    suspend fun sendAlerts(): Int = sendMerchantAlerts()

    suspend fun dailySummaries(): Int = queueDailySummaries()
}
