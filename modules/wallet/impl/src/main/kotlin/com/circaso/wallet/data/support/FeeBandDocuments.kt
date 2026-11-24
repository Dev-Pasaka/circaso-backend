package com.circaso.wallet.data.support

import com.circaso.wallet.domain.models.FeeBand
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** One band as `wallet.fee_rules.bands` keeps it; the bounds trigger reads `rateBps` from the same document. */
@Serializable
internal data class FeeBandDocument(val fromUsdMicros: Long, val toUsdMicros: Long? = null, val rateBps: Int)

/**
 * `wallet.fee_rules.bands` (V15, 2026-11-24), typed both ways (decision 2026-10-27: no untyped JSON). Null is a flat
 * rule. Only this code writes it and the trigger parses it on every insert, so a document that can't be read is a bug
 * and fails loudly rather than pricing at a guessed rate.
 */
internal object FeeBandDocuments {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(FeeBandDocument.serializer())

    fun encode(bands: List<FeeBand>): String? =
        bands.takeIf { it.isNotEmpty() }?.let { list ->
            json.encodeToString(serializer, list.map { FeeBandDocument(it.fromMinor, it.toMinor, it.rateBps) })
        }

    fun decode(stored: String?): List<FeeBand> =
        stored
            ?.let { json.decodeFromString(serializer, it) }
            ?.map { FeeBand(it.fromUsdMicros, it.toUsdMicros, it.rateBps) }
            .orEmpty()
}
