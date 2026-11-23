package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.FeeSource
import com.circaso.integrations.api.ProviderFee
import com.circaso.integrations.api.ProviderFees
import com.circaso.shared.FiatAmount
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tests: the provider's fees as the test sets them. Every amount is charged [collectionFeeMinor] or [payoutFeeMinor]
 * with [source]; set a fee to null (or [source] to `UNKNOWN`) for a provider whose table can't be read, which is what
 * the caller's fallback to the admin's table is tested against.
 */
class FakeProviderFees : ProviderFees {
    val asked: MutableList<Pair<String, FiatAmount>> = CopyOnWriteArrayList()

    @Volatile
    var collectionFeeMinor: Long? = 0

    @Volatile
    var payoutFeeMinor: Long? = 0

    @Volatile
    var source: FeeSource = FeeSource.LIVE_TABLE

    override suspend fun collectionFee(provider: String, amount: FiatAmount): ProviderFee =
        answer(provider, amount, collectionFeeMinor)

    override suspend fun payoutFee(provider: String, amount: FiatAmount): ProviderFee =
        answer(provider, amount, payoutFeeMinor)

    private fun answer(
        provider: String,
        amount: FiatAmount,
        feeMinor: Long?,
    ): ProviderFee {
        asked += provider to amount
        if (feeMinor == null || source == FeeSource.UNKNOWN) return ProviderFee.UNKNOWN
        return ProviderFee(FiatAmount(feeMinor, amount.currency), source)
    }
}
