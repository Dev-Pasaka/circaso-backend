package com.circaso.ops

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import com.circaso.wallet.api.FeeAdmin
import com.circaso.wallet.api.FeeBoundsChange
import com.circaso.wallet.api.FeeRateBounds
import com.circaso.wallet.api.FeeRuleBook
import com.circaso.wallet.api.FeeRuleChange
import com.circaso.wallet.api.FeeRuleHistory
import com.circaso.wallet.api.FeeRuleKind
import com.circaso.wallet.api.FeeRuleScope
import com.circaso.wallet.api.FeeRuleTerms
import com.circaso.wallet.api.FeeRuleVersion
import com.circaso.wallet.api.LimitRuleBook
import com.circaso.wallet.api.LimitRuleChange
import com.circaso.wallet.api.LimitRuleHistory
import com.circaso.wallet.api.LimitRuleScope
import com.circaso.wallet.api.LimitRuleTerms
import com.circaso.wallet.api.LimitRuleVersion
import com.circaso.wallet.api.RuleStatus
import com.circaso.wallet.api.WalletOwnerType
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The wallet's fee and limit rules in memory: versions append, a stale base version is refused, nothing else. */
class FakeFeeAdmin : FeeAdmin {
    val fees = ConcurrentHashMap<UUID, List<FeeRuleVersion>>()
    val limits = ConcurrentHashMap<UUID, List<LimitRuleVersion>>()
    var bounds = FeeRateBounds(0, 200, EPOCH)

    val feeRule: UUID = UUID.fromString("0195f2f0-0000-7000-8000-000000000001")
    val limitRule: UUID = UUID.fromString("0195f2f0-0000-7000-8000-000000000101")

    init {
        fees[feeRule] =
            listOf(
                FeeRuleVersion(
                    feeRule,
                    1,
                    FeeRuleScope(FeeRuleKind.INTERNAL, null, null, null, null, null),
                    FeeRuleTerms(50, 10_000_000_000, null, 2_500, 2_000),
                    EPOCH,
                    null,
                    "Between Circaso wallets",
                    EPOCH,
                ),
            )
        limits[limitRule] =
            listOf(
                LimitRuleVersion(
                    limitRule,
                    1,
                    LimitRuleScope(WalletOwnerType.PERSON, null, null),
                    LimitRuleTerms(500_000_000, 1_000_000_000, 24),
                    EPOCH,
                    null,
                    "Personal placeholders",
                    EPOCH,
                ),
            )
    }

    override suspend fun feeRules() =
        FeeRuleBook(
            EPOCH,
            bounds,
            fees.map { (id, versions) -> FeeRuleHistory(id, RuleStatus.ACTIVE, versions.last().version, versions) },
        )

    override suspend fun createFeeRule(
        scope: FeeRuleScope,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<FeeRuleChange> {
        val id = UUID.randomUUID()
        val version = FeeRuleVersion(id, 1, scope, terms, effectiveFrom ?: EPOCH, null, note, EPOCH)
        fees[id] = listOf(version)
        return Resource.success(FeeRuleChange(null, version))
    }

    override suspend fun changeFeeRule(
        ruleId: UUID,
        baseVersion: Int,
        terms: FeeRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<FeeRuleChange> {
        val versions = fees[ruleId] ?: return Resource.error(WalletCode("wallet.rule_not_found", ErrorKind.NOT_FOUND))
        val latest = versions.last()
        if (latest.version != baseVersion) return Resource.error(WalletCode("wallet.rule_changed", ErrorKind.CONFLICT))
        val from = effectiveFrom ?: EPOCH.plusSeconds(60)
        val ended = latest.copy(effectiveTo = from)
        val next = latest.copy(version = latest.version + 1, terms = terms, effectiveFrom = from, note = note)
        fees[ruleId] = versions.dropLast(1) + ended + next
        return Resource.success(FeeRuleChange(latest, next))
    }

    override suspend fun endFeeRule(
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
    ): Resource<FeeRuleChange> {
        val latest =
            fees[ruleId]?.last() ?: return Resource.error(WalletCode("wallet.rule_not_found", ErrorKind.NOT_FOUND))
        return Resource.success(FeeRuleChange(latest, latest.copy(effectiveTo = effectiveTo ?: EPOCH)))
    }

    override suspend fun updateFeeBounds(minRateBps: Int, maxRateBps: Int): Resource<FeeBoundsChange> {
        val before = bounds
        bounds = FeeRateBounds(minRateBps, maxRateBps, EPOCH)
        return Resource.success(FeeBoundsChange(before, bounds))
    }

    override suspend fun limitRules() =
        LimitRuleBook(
            EPOCH,
            limits.map { (id, versions) -> LimitRuleHistory(id, RuleStatus.ACTIVE, versions.last().version, versions) },
        )

    override suspend fun createLimitRule(
        scope: LimitRuleScope,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<LimitRuleChange> {
        val id = UUID.randomUUID()
        val version = LimitRuleVersion(id, 1, scope, terms, effectiveFrom ?: EPOCH, null, note, EPOCH)
        limits[id] = listOf(version)
        return Resource.success(LimitRuleChange(null, version))
    }

    override suspend fun changeLimitRule(
        ruleId: UUID,
        baseVersion: Int,
        terms: LimitRuleTerms,
        effectiveFrom: Instant?,
        note: String,
    ): Resource<LimitRuleChange> {
        val versions = limits[ruleId] ?: return Resource.error(WalletCode("wallet.rule_not_found", ErrorKind.NOT_FOUND))
        val latest = versions.last()
        val next = latest.copy(version = latest.version + 1, terms = terms, note = note)
        limits[ruleId] = versions + next
        return Resource.success(LimitRuleChange(latest, next))
    }

    override suspend fun endLimitRule(
        ruleId: UUID,
        baseVersion: Int,
        effectiveTo: Instant?,
    ): Resource<LimitRuleChange> {
        val latest =
            limits[ruleId]?.last() ?: return Resource.error(WalletCode("wallet.rule_not_found", ErrorKind.NOT_FOUND))
        return Resource.success(LimitRuleChange(latest, latest.copy(effectiveTo = effectiveTo ?: EPOCH)))
    }
}

/** One of the wallet's codes, as the fake answers it. */
data class WalletCode(override val code: String, override val kind: ErrorKind) : DomainError {
    override val message: String = code
}
