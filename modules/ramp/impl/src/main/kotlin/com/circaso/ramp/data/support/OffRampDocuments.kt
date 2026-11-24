package com.circaso.ramp.data.support

import com.circaso.ramp.domain.models.PayoutFeeBand
import com.circaso.ramp.domain.models.RampFeeBand
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

/** One band of a ramp fee as `ramp.settings.onramp_fee_bands` and `offramp_fee_bands` keep it (V15, 2026-11-24). */
@Serializable
internal data class RampFeeBandDocument(val fromMinor: String, val toMinor: String? = null, val feeBps: Int)

/**
 * The ramp's fee bands, typed both ways. `[]` is one band at `fee_bps`. Only this code (and V15, which wrote today's
 * `fee_bps` as one band) writes them, so a list that can't be read is a bug and fails loudly rather than pricing at a
 * guessed rate.
 */
internal object RampFeeBandDocuments {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(RampFeeBandDocument.serializer())

    fun encode(bands: List<RampFeeBand>): String =
        json.encodeToString(
            serializer,
            bands.map { RampFeeBandDocument(it.fromMinor.toString(), it.toMinor?.toString(), it.feeBps) },
        )

    fun decode(stored: String): List<RampFeeBand> =
        json.decodeFromString(serializer, stored).map { band ->
            RampFeeBand(band.fromMinor.toLong(), band.toMinor?.toLong(), band.feeBps)
        }
}
