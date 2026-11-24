package com.circaso.ramp.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.ramp.data.tables.PricingSettingsTable
import com.circaso.ramp.domain.models.PricingSettings
import com.circaso.ramp.domain.models.RateMode
import com.circaso.ramp.domain.repositories.PricingSettingsRepository
import com.circaso.shared.Network
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.math.BigDecimal

/** `ramp.pricing_settings`: a change is `SET version = version + 1 WHERE network = ? [AND version = ?]`. */
class PricingRepositoryImpl(private val database: Database) : PricingSettingsRepository {
    override suspend fun load(): Map<Network, PricingSettings> =
        database.tx("pricing-settings.load") {
            PricingSettingsTable
                .selectAll()
                .map { it.toSettings() }
                .toList()
                .filterNotNull()
                .associateBy { it.network }
        }

    override suspend fun seed(settings: PricingSettings) {
        database.tx("pricing-settings.seed") {
            PricingSettingsTable.insertIgnore {
                it[network] = settings.network.name
                it[version] = settings.version
                it.write(settings)
            }
        }
    }

    override suspend fun update(settings: PricingSettings, expectedVersion: Long?): PricingSettings? =
        database.tx("pricing-settings.update") {
            PricingSettingsTable
                .updateReturning(where = {
                    val row = PricingSettingsTable.network eq settings.network.name
                    if (expectedVersion == null) row else row and (PricingSettingsTable.version eq expectedVersion)
                }) {
                    it.write(settings)
                    it[version] = version + 1
                }.map { it.toSettings() }
                .singleOrNull()
        }

    private fun UpdateBuilder<*>.write(settings: PricingSettings) {
        this[PricingSettingsTable.mode] = settings.mode.name
        this[PricingSettingsTable.buySpreadBps] = settings.buySpreadBps
        this[PricingSettingsTable.sellSpreadBps] = settings.sellSpreadBps
        this[PricingSettingsTable.manualBuyRate] = settings.manualBuyRate?.toPlainString()
        this[PricingSettingsTable.manualSellRate] = settings.manualSellRate?.toPlainString()
        this[PricingSettingsTable.manualExpiresAt] = settings.manualExpiresAt
        this[PricingSettingsTable.maxDeviationBps] = settings.maxDeviationBps
        this[PricingSettingsTable.maxReferenceAgeHours] = settings.maxReferenceAgeHours
        this[PricingSettingsTable.updatedBy] = settings.updatedBy
        this[PricingSettingsTable.reason] = settings.reason
        this[PricingSettingsTable.updatedAt] = settings.updatedAt
    }

    private fun ResultRow.toSettings(): PricingSettings? {
        val network = Network.parse(this[PricingSettingsTable.network]) ?: return null
        val mode = RateMode.entries.firstOrNull { it.name == this[PricingSettingsTable.mode] } ?: return null
        return PricingSettings(
            network = network,
            mode = mode,
            buySpreadBps = this[PricingSettingsTable.buySpreadBps],
            sellSpreadBps = this[PricingSettingsTable.sellSpreadBps],
            manualBuyRate = this[PricingSettingsTable.manualBuyRate]?.let(::BigDecimal),
            manualSellRate = this[PricingSettingsTable.manualSellRate]?.let(::BigDecimal),
            manualExpiresAt = this[PricingSettingsTable.manualExpiresAt],
            maxDeviationBps = this[PricingSettingsTable.maxDeviationBps],
            maxReferenceAgeHours = this[PricingSettingsTable.maxReferenceAgeHours],
            version = this[PricingSettingsTable.version],
            updatedBy = this[PricingSettingsTable.updatedBy],
            reason = this[PricingSettingsTable.reason],
            updatedAt = this[PricingSettingsTable.updatedAt],
        )
    }
}
