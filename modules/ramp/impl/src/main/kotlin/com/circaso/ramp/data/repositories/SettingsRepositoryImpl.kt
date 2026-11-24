package com.circaso.ramp.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.integrations.api.KycEnvironment
import com.circaso.ramp.data.support.PayoutFeeBands
import com.circaso.ramp.data.support.RampFeeBandDocuments
import com.circaso.ramp.data.tables.PreviewAccessTable
import com.circaso.ramp.data.tables.SettingsTable
import com.circaso.ramp.domain.models.PreviewAccess
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.models.RampState
import com.circaso.ramp.domain.repositories.PreviewAccessRepository
import com.circaso.ramp.domain.repositories.SettingsRepository
import com.circaso.shared.Network
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.plus
import org.jetbrains.exposed.v1.core.statements.UpdateBuilder
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.updateReturning
import java.time.Instant

/**
 * `ramp.settings`: one row per network. A change is `SET version = version + 1 WHERE version = ?`, so two staff
 * saving at once can't overwrite each other: the second finds no row and is told the settings changed.
 */
class SettingsRepositoryImpl(private val database: Database) : SettingsRepository {
    override suspend fun load(): Map<Network, RampSettings> =
        database.tx("settings.load") {
            SettingsTable
                .selectAll()
                .map { it.toSettings() }
                .toList()
                .filterNotNull()
                .associateBy { it.network }
        }

    override suspend fun seed(settings: RampSettings) {
        database.tx("settings.seed") {
            SettingsTable.insertIgnore {
                it[network] = settings.network.name
                it.write(settings)
                it[walletSetId] = settings.walletSetId
                it[updatedBy] = settings.updatedBy
                it[updatedAt] = settings.updatedAt
                it[version] = settings.version
            }
        }
    }

    override suspend fun update(
        settings: RampSettings,
        expectedVersion: Long?,
        by: String,
        at: Instant,
    ): RampSettings? =
        database.tx("settings.update") {
            SettingsTable
                .updateReturning(where = {
                    val row = SettingsTable.network eq settings.network.name
                    if (expectedVersion == null) row else row and (SettingsTable.version eq expectedVersion)
                }) {
                    it.write(settings)
                    it[updatedBy] = by
                    it[updatedAt] = at
                    it[version] = version + 1
                }.map { it.toSettings() }
                .singleOrNull()
        }

    override suspend fun setWalletSet(network: Network, walletSetId: String) {
        database.tx("settings.set-wallet-set") {
            SettingsTable.update({ (SettingsTable.network eq network.name) and SettingsTable.walletSetId.isNull() }) {
                it[SettingsTable.walletSetId] = walletSetId
            }
        }
    }

    /** The columns staff edit; the wallet set, the version and who changed it are written by their own steps. */
    private fun UpdateBuilder<*>.write(settings: RampSettings) {
        this[SettingsTable.state] = settings.state.name
        this[SettingsTable.chains] = settings.chains.joinToString(",")
        this[SettingsTable.orderMinMinor] = settings.orderMinMinor
        this[SettingsTable.orderMaxMinor] = settings.orderMaxMinor
        this[SettingsTable.dailyLimitMinor] = settings.dailyLimitMinor
        this[SettingsTable.promptsPerHour] = settings.promptsPerHour
        this[SettingsTable.feeBps] = settings.feeBps
        this[SettingsTable.merchantShareBps] = settings.merchantShareBps
        this[SettingsTable.feeSettleHourUtc] = settings.feeSettleHourUtc
        this[SettingsTable.quoteSeconds] = settings.quoteSeconds
        this[SettingsTable.paymentWindowSeconds] = settings.paymentWindowSeconds
        this[SettingsTable.kycRequired] = settings.kycRequired
        this[SettingsTable.callbackBaseUrl] = settings.callbackBaseUrl
        this[SettingsTable.collectionsProvider] = settings.collectionsProvider
        this[SettingsTable.providerFeeFloorMinor] = settings.providerFeeFloorMinor
        this[SettingsTable.kycProvider] = settings.kycProvider
        this[SettingsTable.kycEnvironment] = settings.kycEnvironment.name
        this[SettingsTable.kycWorkflowUser] = settings.kycWorkflowUser
        this[SettingsTable.kycWorkflowMerchant] = settings.kycWorkflowMerchant
        this[SettingsTable.merchantPortalUrl] = settings.merchantPortalUrl
        this[SettingsTable.kycAttemptsPerDay] = settings.kycAttemptsPerDay
        this[SettingsTable.kycPendingHours] = settings.kycPendingHours
        this[SettingsTable.offrampState] = settings.offRampState.name
        this[SettingsTable.offrampMinMinor] = settings.offRampMinMinor
        this[SettingsTable.offrampMaxMinor] = settings.offRampMaxMinor
        this[SettingsTable.offrampDailyLimitMinor] = settings.offRampDailyLimitMinor
        this[SettingsTable.transferWindowSeconds] = settings.transferWindowSeconds
        this[SettingsTable.payoutWindowSeconds] = settings.payoutWindowSeconds
        this[SettingsTable.payoutFeeBands] = PayoutFeeBands.encode(settings.payoutFeeBands)
        this[SettingsTable.onrampFeeBands] = RampFeeBandDocuments.encode(settings.onRampFeeBands)
        this[SettingsTable.offrampFeeBands] = RampFeeBandDocuments.encode(settings.offRampFeeBands)
        this[SettingsTable.payoutBalanceLowMinor] = settings.payoutBalanceLowMinor
        this[SettingsTable.withdrawDestinationDelayHours] = settings.withdrawDestinationDelayHours
        this[SettingsTable.withdrawMinMinor] = settings.withdrawMinMinor
        this[SettingsTable.withdrawDailyMaxMinor] = settings.withdrawDailyMaxMinor
        this[SettingsTable.onlineSessionMaxDays] = settings.onlineSessionMaxDays
    }

    private fun ResultRow.toSettings(): RampSettings? {
        val network = Network.parse(this[SettingsTable.network]) ?: return null
        return RampSettings(
            network = network,
            state = RampState.valueOf(this[SettingsTable.state]),
            chains = this[SettingsTable.chains].split(',').filter { it.isNotBlank() },
            orderMinMinor = this[SettingsTable.orderMinMinor],
            orderMaxMinor = this[SettingsTable.orderMaxMinor],
            dailyLimitMinor = this[SettingsTable.dailyLimitMinor],
            promptsPerHour = this[SettingsTable.promptsPerHour],
            feeBps = this[SettingsTable.feeBps],
            merchantShareBps = this[SettingsTable.merchantShareBps],
            feeSettleHourUtc = this[SettingsTable.feeSettleHourUtc],
            quoteSeconds = this[SettingsTable.quoteSeconds],
            paymentWindowSeconds = this[SettingsTable.paymentWindowSeconds],
            kycRequired = this[SettingsTable.kycRequired],
            callbackBaseUrl = this[SettingsTable.callbackBaseUrl],
            collectionsProvider = this[SettingsTable.collectionsProvider],
            providerFeeFloorMinor = this[SettingsTable.providerFeeFloorMinor],
            walletSetId = this[SettingsTable.walletSetId],
            updatedBy = this[SettingsTable.updatedBy],
            updatedAt = this[SettingsTable.updatedAt],
            version = this[SettingsTable.version],
            kycProvider = this[SettingsTable.kycProvider],
            kycEnvironment =
                KycEnvironment.parse(this[SettingsTable.kycEnvironment]) ?: RampSettings.environmentOf(network),
            kycWorkflowUser = this[SettingsTable.kycWorkflowUser],
            kycWorkflowMerchant = this[SettingsTable.kycWorkflowMerchant],
            merchantPortalUrl = this[SettingsTable.merchantPortalUrl],
            kycAttemptsPerDay = this[SettingsTable.kycAttemptsPerDay],
            kycPendingHours = this[SettingsTable.kycPendingHours],
            offRampState = RampState.valueOf(this[SettingsTable.offrampState]),
            offRampMinMinor = this[SettingsTable.offrampMinMinor],
            offRampMaxMinor = this[SettingsTable.offrampMaxMinor],
            offRampDailyLimitMinor = this[SettingsTable.offrampDailyLimitMinor],
            transferWindowSeconds = this[SettingsTable.transferWindowSeconds],
            payoutWindowSeconds = this[SettingsTable.payoutWindowSeconds],
            payoutFeeBands = PayoutFeeBands.decode(this[SettingsTable.payoutFeeBands]),
            payoutBalanceLowMinor = this[SettingsTable.payoutBalanceLowMinor],
            withdrawDestinationDelayHours = this[SettingsTable.withdrawDestinationDelayHours],
            withdrawMinMinor = this[SettingsTable.withdrawMinMinor],
            withdrawDailyMaxMinor = this[SettingsTable.withdrawDailyMaxMinor],
            onlineSessionMaxDays = this[SettingsTable.onlineSessionMaxDays],
            onRampFeeBands = RampFeeBandDocuments.decode(this[SettingsTable.onrampFeeBands]),
            offRampFeeBands = RampFeeBandDocuments.decode(this[SettingsTable.offrampFeeBands]),
        )
    }
}

class PreviewAccessRepositoryImpl(private val database: Database) : PreviewAccessRepository {
    override suspend fun has(userId: UserId, network: Network): Boolean =
        database.tx("preview.has") {
            PreviewAccessTable
                .selectAll()
                .where { (PreviewAccessTable.userId eq userId.value) and (PreviewAccessTable.network eq network.name) }
                .map { true }
                .singleOrNull() ?: false
        }

    override suspend fun grant(access: PreviewAccess): Boolean =
        database.tx("preview.grant") {
            PreviewAccessTable
                .insertIgnore {
                    it[userId] = access.userId.value
                    it[network] = access.network.name
                    it[grantedBy] = access.grantedBy
                    it[createdAt] = access.at
                }.insertedCount > 0
        }

    override suspend fun revoke(userId: UserId, network: Network): Boolean =
        database.tx("preview.revoke") {
            PreviewAccessTable.deleteWhere {
                (PreviewAccessTable.userId eq userId.value) and (PreviewAccessTable.network eq network.name)
            } > 0
        }

    override suspend fun list(network: Network, limit: Int): List<PreviewAccess> =
        database.tx("preview.list") {
            PreviewAccessTable
                .selectAll()
                .where { PreviewAccessTable.network eq network.name }
                .orderBy(PreviewAccessTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map {
                    PreviewAccess(
                        UserId(it[PreviewAccessTable.userId]),
                        network,
                        it[PreviewAccessTable.grantedBy],
                        it[PreviewAccessTable.createdAt],
                    )
                }.toList()
        }
}
