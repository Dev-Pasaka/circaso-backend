package com.circaso.ops.presentation.mappers

import com.circaso.ops.presentation.dto.FeeBoundsChangeResponse
import com.circaso.ops.presentation.dto.FeeBoundsDto
import com.circaso.ops.presentation.dto.FeeRuleChangeResponse
import com.circaso.ops.presentation.dto.FeeRuleDto
import com.circaso.ops.presentation.dto.FeeRuleVersionDto
import com.circaso.ops.presentation.dto.FeeRulesResponse
import com.circaso.ops.presentation.dto.FeeScopeDto
import com.circaso.ops.presentation.dto.FeeTermsDto
import com.circaso.ops.presentation.dto.LimitRuleChangeResponse
import com.circaso.ops.presentation.dto.LimitRuleDto
import com.circaso.ops.presentation.dto.LimitRuleVersionDto
import com.circaso.ops.presentation.dto.LimitRulesResponse
import com.circaso.ops.presentation.dto.LimitScopeDto
import com.circaso.ops.presentation.dto.LimitTermsDto
import com.circaso.ops.presentation.dto.NetworkCostDto
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.wallet.api.FeeBoundsChange
import com.circaso.wallet.api.FeeRateBounds
import com.circaso.wallet.api.FeeRuleBook
import com.circaso.wallet.api.FeeRuleChange
import com.circaso.wallet.api.FeeRuleKind
import com.circaso.wallet.api.FeeRuleScope
import com.circaso.wallet.api.FeeRuleTerms
import com.circaso.wallet.api.FeeRuleVersion
import com.circaso.wallet.api.LimitRuleBook
import com.circaso.wallet.api.LimitRuleChange
import com.circaso.wallet.api.LimitRuleScope
import com.circaso.wallet.api.LimitRuleTerms
import com.circaso.wallet.api.LimitRuleVersion
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.api.WalletOwnerType
import java.time.Instant

// Fees and limits between the wire and the wallet's staff contract: enums by name, ids parsed, times as instants. A
// value that isn't one answers 400 request.malformed naming the field; the business rules are the wallet's.

fun FeeScopeDto.toScope(): Resource<FeeRuleScope> =
    parseEnum<FeeRuleKind>(kind, "scope.kind").flatMap { kind ->
        parseEnum<WalletOwnerType>(senderType, "scope.senderType").flatMap { sender ->
            parseEnum<WalletOwnerType>(receiverType, "scope.receiverType").flatMap { receiver ->
                walletIdOf(walletId).map { wallet -> FeeRuleScope(kind, sender, receiver, blockchain, asset, wallet) }
            }
        }
    }

@Suppress("DEPRECATION")
fun FeeTermsDto.toTerms() = FeeRuleTerms(rateBps, capBaseUsdMicros, minFeeUsdMicros, gasShareMaxBps, gasMarginBps)

fun LimitScopeDto.toScope(): Resource<LimitRuleScope> =
    parseEnum<WalletOwnerType>(ownerType, "scope.ownerType").flatMap { owner ->
        walletIdOf(walletId).map { wallet -> LimitRuleScope(owner, wallet, asset) }
    }

fun LimitTermsDto.toTerms() = LimitRuleTerms(perPaymentMaxUsdMicros, dailyMaxUsdMicros, windowHours)

/** An optional instant: absent means now; a date alone isn't taken, so a time is never guessed. */
fun parseWhen(raw: String?, field: String): Resource<Instant?> {
    val value = raw?.trim()?.ifEmpty { null } ?: return Resource.success(null)
    return runCatching { Instant.parse(value) }
        .getOrNull()
        ?.let { Resource.success(it) }
        ?: malformed(field, "must be an instant (2026-11-09T08:00:00Z)")
}

private fun walletIdOf(raw: String?): Resource<WalletId?> {
    val value = raw?.trim()?.ifEmpty { null } ?: return Resource.success(null)
    return WalletId.parse(value)?.let { Resource.success(it) } ?: malformed("scope.walletId", "must be a wallet id")
}

fun FeeRateBounds.toDto() = FeeBoundsDto(minRateBps, maxRateBps, updatedAt.toString())

@Suppress("DEPRECATION")
fun FeeRuleVersion.toDto() =
    FeeRuleVersionDto(
        ruleId = ruleId.toString(),
        version = version,
        scope =
            FeeScopeDto(
                kind = scope.kind?.name,
                senderType = scope.senderType?.name,
                receiverType = scope.receiverType?.name,
                blockchain = scope.blockchain,
                asset = scope.asset,
                walletId = scope.walletId?.toString(),
            ),
        terms =
            FeeTermsDto(
                rateBps = terms.rateBps,
                capBaseUsdMicros = terms.capBaseUsdMicros,
                minFeeUsdMicros = terms.minFeeUsdMicros,
                gasShareMaxBps = terms.gasShareMaxBps,
                gasMarginBps = terms.gasMarginBps,
            ),
        effectiveFrom = effectiveFrom.toString(),
        effectiveTo = effectiveTo?.toString(),
        note = note,
        createdAt = createdAt?.toString(),
    )

fun FeeRuleBook.toResponse() =
    FeeRulesResponse(
        at = at.toString(),
        bounds = bounds.toDto(),
        rules =
            rules.map { rule ->
                FeeRuleDto(rule.ruleId.toString(), rule.status.name, rule.current, rule.versions.map { it.toDto() })
            },
        networkCosts =
            networkCosts.map {
                NetworkCostDto(it.blockchain, it.asset, it.gasUsdMicros, it.source.name, it.samples, it.at.toString())
            },
    )

fun FeeRuleChange.toResponse() = FeeRuleChangeResponse(before?.toDto(), after.toDto())

fun FeeBoundsChange.toResponse() = FeeBoundsChangeResponse(before.toDto(), after.toDto())

fun LimitRuleVersion.toDto() =
    LimitRuleVersionDto(
        ruleId = ruleId.toString(),
        version = version,
        scope = LimitScopeDto(scope.ownerType?.name, scope.walletId?.toString(), scope.asset),
        terms = LimitTermsDto(terms.perPaymentMaxUsdMicros, terms.dailyMaxUsdMicros, terms.windowHours),
        effectiveFrom = effectiveFrom.toString(),
        effectiveTo = effectiveTo?.toString(),
        note = note,
        createdAt = createdAt?.toString(),
    )

fun LimitRuleBook.toResponse() =
    LimitRulesResponse(
        at = at.toString(),
        rules =
            rules.map { rule ->
                LimitRuleDto(rule.ruleId.toString(), rule.status.name, rule.current, rule.versions.map { it.toDto() })
            },
    )

fun LimitRuleChange.toResponse() = LimitRuleChangeResponse(before?.toDto(), after.toDto())
