package com.circaso.ramp.data.support

import com.circaso.ramp.domain.models.PayoutFeeBand
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One band of staff's payout fee table as `ramp.settings.payout_fee_bands` keeps it: minor units as strings. */
@Serializable
internal data class PayoutFeeBandDocument(val upToMinor: String, val feeMinor: String)

/**
 * `ramp.settings.payout_fee_bands`, typed both ways (decision 2026-10-27: no untyped JSON). A row that can't be read
 * is no table at all: a sale is then quoted only from the provider's own table, never from a guess.
 */
internal object PayoutFeeBands {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PayoutFeeBandDocument.serializer())

    fun encode(bands: List<PayoutFeeBand>): String =
        json.encodeToString(
            serializer,
            bands.map { PayoutFeeBandDocument(it.upToMinor.toString(), it.feeMinor.toString()) },
        )

    fun decode(stored: String): List<PayoutFeeBand> {
        val documents = runCatching { json.decodeFromString(serializer, stored) }.getOrNull() ?: return emptyList()
        return documents.mapNotNull { band ->
            val upTo = band.upToMinor.toLongOrNull() ?: return@mapNotNull null
            val fee = band.feeMinor.toLongOrNull() ?: return@mapNotNull null
            PayoutFeeBand(upTo, fee)
        }
    }
}
