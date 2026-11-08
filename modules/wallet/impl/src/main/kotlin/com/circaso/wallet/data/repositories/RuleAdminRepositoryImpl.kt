package com.circaso.wallet.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseException
import com.circaso.wallet.data.tables.FeeBoundsTable
import com.circaso.wallet.data.tables.FeeRulesTable
import com.circaso.wallet.data.tables.LimitRulesTable
import com.circaso.wallet.domain.models.FeeBounds
import com.circaso.wallet.domain.models.FeeRule
import com.circaso.wallet.domain.models.LimitRule
import com.circaso.wallet.domain.models.VersionWrite
import com.circaso.wallet.domain.repositories.RuleAdminRepository
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Micro-dollars as the rules store them: dollars with six decimals, exactly. */
private fun Long.toDollars(): BigDecimal = BigDecimal.valueOf(this, MICRO_DIGITS)

private const val MICRO_DIGITS = 6
private const val BOUNDS_ROW: Short = 1

/**
 * Staff writes to the rules (decision 2026-11-08), always inside the use case's transaction. A failed insert is
 * answered, not thrown: the use case then returns an error, which rolls the whole transaction back.
 */
class RuleAdminRepositoryImpl(private val database: Database) : RuleAdminRepository {
    override suspend fun feeBounds(): FeeBounds =
        database.tx("fee-bounds.find") {
            FeeBoundsTable
                .selectAll()
                .where { FeeBoundsTable.id eq BOUNDS_ROW }
                .map { it.toBounds() }
                .single()
        }

    override suspend fun lockFeeBounds(): FeeBounds =
        database.tx("fee-bounds.lock") {
            FeeBoundsTable
                .selectAll()
                .where { FeeBoundsTable.id eq BOUNDS_ROW }
                .forUpdate(ForUpdateOption.ForUpdate)
                .map { it.toBounds() }
                .single()
        }

    override suspend fun feeVersions(ruleId: UUID): List<FeeRule> =
        database.tx("fee-rules.versions") {
            FeeRulesTable
                .selectAll()
                .where { FeeRulesTable.ruleId eq ruleId }
                .map { it.toFeeRule() }
                .toList()
        }

    override suspend fun insertFeeVersion(rule: FeeRule): VersionWrite =
        try {
            database.tx("fee-rules.insert") {
                FeeRulesTable.insert {
                    it[ruleId] = rule.ruleId
                    it[version] = rule.version
                    it[kind] = rule.kind?.name
                    it[senderType] = rule.senderType?.name
                    it[receiverType] = rule.receiverType?.name
                    it[blockchain] = rule.blockchain
                    it[asset] = rule.asset
                    it[walletId] = rule.walletId?.value
                    it[rateBps] = rule.rateBps
                    it[capBaseUsd] = rule.capBaseMinor.toDollars()
                    it[minFeeUsd] = rule.minFeeMinor?.toDollars()
                    it[gasShareMaxBps] = rule.gasShareMaxBps
                    it[gasMarginBps] = rule.gasMarginBps
                    it[effectiveFrom] = rule.effectiveFrom
                    it[effectiveTo] = rule.effectiveTo
                    it[note] = rule.note
                    it[createdAt] = checkNotNull(rule.createdAt) { "A new version carries its creation time" }
                }
            }
            VersionWrite.WRITTEN
        } catch (e: DatabaseException) {
            refused(e)
        }

    override suspend fun endFeeVersion(
        ruleId: UUID,
        version: Int,
        at: Instant,
    ): Boolean =
        database.tx("fee-rules.end") {
            FeeRulesTable.update({ (FeeRulesTable.ruleId eq ruleId) and (FeeRulesTable.version eq version) }) {
                it[effectiveTo] = at
            } == 1
        }

    override suspend fun updateFeeBounds(
        minRateBps: Int,
        maxRateBps: Int,
        at: Instant,
    ): FeeBounds =
        database.tx("fee-bounds.update") {
            FeeBoundsTable.update({ FeeBoundsTable.id eq BOUNDS_ROW }) {
                it[this.minRateBps] = minRateBps
                it[this.maxRateBps] = maxRateBps
                it[updatedAt] = at
            }
            FeeBounds(minRateBps, maxRateBps, at)
        }

    override suspend fun lockLimitRule(ruleId: UUID): Boolean =
        database.tx("limit-rules.lock") {
            LimitRulesTable
                .select(LimitRulesTable.version)
                .where { LimitRulesTable.ruleId eq ruleId }
                .forUpdate(ForUpdateOption.ForUpdate)
                .toList()
                .isNotEmpty()
        }

    override suspend fun limitVersions(ruleId: UUID): List<LimitRule> =
        database.tx("limit-rules.versions") {
            LimitRulesTable
                .selectAll()
                .where { LimitRulesTable.ruleId eq ruleId }
                .map { it.toLimitRule() }
                .toList()
        }

    override suspend fun insertLimitVersion(rule: LimitRule): VersionWrite =
        try {
            database.tx("limit-rules.insert") {
                LimitRulesTable.insert {
                    it[ruleId] = rule.ruleId
                    it[version] = rule.version
                    it[ownerType] = rule.ownerType?.name
                    it[walletId] = rule.walletId?.value
                    it[asset] = rule.asset
                    it[perPaymentMaxUsd] = rule.perPaymentMaxMinor?.toDollars()
                    it[dailyMaxUsd] = rule.dailyMaxMinor?.toDollars()
                    it[windowHours] = rule.window.toHours().toInt()
                    it[effectiveFrom] = rule.effectiveFrom
                    it[effectiveTo] = rule.effectiveTo
                    it[note] = rule.note
                    it[createdAt] = checkNotNull(rule.createdAt) { "A new version carries its creation time" }
                }
            }
            VersionWrite.WRITTEN
        } catch (e: DatabaseException) {
            refused(e)
        }

    override suspend fun endLimitVersion(
        ruleId: UUID,
        version: Int,
        at: Instant,
    ): Boolean =
        database.tx("limit-rules.end") {
            LimitRulesTable.update({ (LimitRulesTable.ruleId eq ruleId) and (LimitRulesTable.version eq version) }) {
                it[effectiveTo] = at
            } == 1
        }

    /**
     * A lost (rule, version) is a racing writer; a check with no constraint name is the bounds trigger
     * (`fee_rules_in_bounds` raises `check_violation`). Anything else is a bug and is thrown.
     */
    private fun refused(e: DatabaseException): VersionWrite =
        when {
            e.isUniqueViolation -> VersionWrite.TAKEN
            e.isCheckViolation && e.constraint == null -> VersionWrite.OUT_OF_BOUNDS
            else -> throw e
        }

    private fun ResultRow.toBounds() =
        FeeBounds(
            minRateBps = this[FeeBoundsTable.minRateBps],
            maxRateBps = this[FeeBoundsTable.maxRateBps],
            updatedAt = this[FeeBoundsTable.updatedAt],
        )
}
