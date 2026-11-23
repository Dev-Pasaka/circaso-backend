package com.circaso.ops.domain.models

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.api.OpsEngineSettings
import com.circaso.ramp.api.OpsRampOrderCursor
import com.circaso.ramp.api.OpsRampPeriod
import com.circaso.shared.Network

/** Which of a ramp merchant's orders staff ask for: any day when [period] is null, any state when [state] is. */
data class RampMerchantOrdersQuery(
    val merchantId: MerchantId,
    val network: Network,
    val period: OpsRampPeriod?,
    val state: String?,
    val after: OpsRampOrderCursor?,
)

/** A change of a network and direction's engine settings: the document, the version it was read at, and why. */
data class RampEngineSettingsUpdate(
    val network: Network,
    val direction: String,
    val settings: OpsEngineSettings,
    val version: Long?,
    val reason: String,
)

/** Staff's call on a merchant: their type and, when given, a limit of their own in KES minor units. */
data class RampMerchantTierUpdate(
    val merchantId: MerchantId,
    val merchantType: String,
    val orderLimitMinor: Long?,
    val reason: String,
)
