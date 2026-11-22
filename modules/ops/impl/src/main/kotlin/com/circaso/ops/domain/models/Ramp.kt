package com.circaso.ops.domain.models

import com.circaso.ramp.api.MerchantId
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
