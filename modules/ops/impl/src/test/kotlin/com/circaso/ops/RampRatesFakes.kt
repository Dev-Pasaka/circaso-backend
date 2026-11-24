package com.circaso.ops

import com.circaso.ramp.api.OpsPricingChange
import com.circaso.ramp.api.OpsPricingSettings
import com.circaso.ramp.api.OpsRates
import com.circaso.ramp.api.OpsSideRate
import com.circaso.ramp.api.RampRatesAdmin
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind
import com.circaso.shared.FieldError
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private data class RatesRefusal(
    override val code: String,
    override val kind: ErrorKind,
    override val details: ErrorDetails? = null,
) : DomainError {
    override val message: String = code
}

/**
 * The ramp's rates contract in memory: it keeps what it is given and says what it was asked, so a test sees the ops
 * routes' own work (who may, the shapes, the reason, the audit line). The ramp's own rules are the ramp's tests'; here
 * a buy spread above 2000 stands in for "the ramp refused it".
 */
class FakeRampRatesAdmin : RampRatesAdmin {
    val rows = ConcurrentHashMap<Network, OpsPricingSettings>()
    val updates = CopyOnWriteArrayList<Triple<OpsPricingSettings, String, UUID>>()

    private fun row(network: Network) =
        rows.getOrPut(network) {
            OpsPricingSettings(network, "REFERENCE_SPREAD", 0, 0, null, null, null, 500, 36, 1, null, null, AT)
        }

    override suspend fun pricing(network: Network): Resource<OpsPricingSettings> = Resource.success(row(network))

    override suspend fun updatePricing(
        settings: OpsPricingSettings,
        version: Long?,
        reason: String,
        by: UUID,
    ): Resource<OpsPricingChange> {
        val before = row(settings.network)
        if (settings.buySpreadBps > 2_000) {
            val details = ErrorDetails(fields = listOf(FieldError("buySpreadBps", "must be 0 to 2000 basis points")))
            return Resource.Error(RatesRefusal("ramp.settings_invalid", ErrorKind.INVALID, details))
        }
        if (version != null && version != before.version) {
            return Resource.Error(RatesRefusal("ramp.settings_changed", ErrorKind.CONFLICT))
        }
        val after =
            settings.copy(version = before.version + 1, updatedBy = by.toString(), reason = reason, updatedAt = AT)
        rows[settings.network] = after
        updates += Triple(after, reason, by)
        return Resource.success(OpsPricingChange(before, after))
    }

    override suspend fun rates(network: Network): Resource<OpsRates> {
        val row = row(network)
        return Resource.success(
            OpsRates(
                network = network,
                mode = row.mode,
                referenceRate = "129.25",
                referenceAsOf = AT.minusSeconds(3_600),
                referenceAgeSeconds = 3_600,
                referenceStale = false,
                maxReferenceAgeHours = row.maxReferenceAgeHours,
                maxDeviationBps = row.maxDeviationBps,
                manualExpiresAt = row.manualExpiresAt,
                buy = OpsSideRate("131.18875", "REFERENCE_SPREAD", 150, null),
                sell = OpsSideRate(null, "REFERENCE_SPREAD", null, "REFERENCE_STALE"),
                at = AT,
            ),
        )
    }

    companion object {
        val AT: Instant = Instant.parse("2026-11-24T09:00:00Z")
    }
}
