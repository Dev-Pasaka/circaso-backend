package com.circaso.ramp.domain.support

import com.circaso.ramp.domain.models.RampFeeBand
import com.circaso.ramp.domain.models.RampSettings

/**
 * The ramp fee's amount bands (decision 2026-11-24, Pasaka, "the band sets the rate"): the band an order's KES amount
 * falls in sets the fee rate for the whole amount. Buying and selling each have their own list per network.
 */
object RampFeeBands {
    const val MAX_BANDS = 20

    /** The band [amountMinor] falls in; the first band when it is below every start (never, once they start at 0). */
    fun bandFor(bands: List<RampFeeBand>, amountMinor: Long): RampFeeBand =
        bands.lastOrNull { it.fromMinor <= amountMinor } ?: bands.first()

    /** Whether the order should record which band priced it: only when there is more than the one flat band. */
    fun banded(bands: List<RampFeeBand>): Boolean = bands.size > 1

    /**
     * The first thing wrong with [bands] as `<field>[i].<part>` and the problem, or null. Empty is fine (the flat
     * `feeBps`). They start at 0, each ends where the next starts and above where it starts, only the last is
     * open-ended, every rate is 0 to [RampSettings.MAX_FEE_BPS], and there are at most [MAX_BANDS].
     */
    fun problem(bands: List<RampFeeBand>, field: String): Pair<String, String>? {
        if (bands.isEmpty()) return null
        if (bands.size > MAX_BANDS) return field to "at most $MAX_BANDS bands"
        if (bands.first().fromMinor != 0L) return "$field[0].fromMinor" to "the first band starts at 0"
        bands.forEachIndexed { index, band ->
            bandProblem(bands, index, band, "$field[$index]")?.let { return it }
        }
        return null
    }

    private fun bandProblem(
        bands: List<RampFeeBand>,
        index: Int,
        band: RampFeeBand,
        field: String,
    ): Pair<String, String>? {
        val next = bands.getOrNull(index + 1)
        val to = band.toMinor
        return when {
            band.feeBps !in 0..RampSettings.MAX_FEE_BPS -> {
                "$field.feeBps" to "must be 0 to ${RampSettings.MAX_FEE_BPS}"
            }

            next == null && to != null -> {
                "$field.toMinor" to "the last band has no end"
            }

            next != null && to == null -> {
                "$field.toMinor" to "only the last band has no end"
            }

            to != null && to <= band.fromMinor -> {
                "$field.toMinor" to "must be above fromMinor"
            }

            next != null && next.fromMinor != to -> {
                "${field.substringBeforeLast('[')}[${index + 1}].fromMinor" to
                    "must be where the band before ends"
            }

            else -> {
                null
            }
        }
    }
}
