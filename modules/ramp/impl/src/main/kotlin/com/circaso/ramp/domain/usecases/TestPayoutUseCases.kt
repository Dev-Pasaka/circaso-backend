package com.circaso.ramp.domain.usecases

import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.domain.models.TestPayout
import com.circaso.ramp.domain.support.TestPayouts
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.util.UUID

/**
 * `POST /ramp/merchant/test-payout`: KES 10 to 100 from the merchant's own account at the provider to their own contact
 * phone, never any other number (decision 2026-11-23). Needs the payout account id, a callback address and the merchant
 * online on this node (their keys). Sent once; see [TestPayouts].
 */
class StartTestPayout(private val tests: TestPayouts) {
    suspend operator fun invoke(
        merchantId: MerchantId,
        network: Network,
        amountMinor: Long,
    ): Resource<TestPayout> = tests.start(merchantId, network, amountMinor)
}

/** `GET /ramp/merchant/test-payout`: the merchant's latest test (or [testId]), asking the provider while it is open. */
class GetTestPayout(private val tests: TestPayouts) {
    suspend operator fun invoke(merchantId: MerchantId, testId: UUID?): Resource<TestPayout> =
        tests.view(merchantId, testId)
}
