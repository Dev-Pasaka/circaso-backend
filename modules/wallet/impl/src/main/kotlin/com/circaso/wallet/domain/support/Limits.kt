package com.circaso.wallet.domain.support

import com.circaso.shared.Asset
import com.circaso.shared.ErrorDetails
import com.circaso.shared.FieldError
import com.circaso.shared.Resource
import com.circaso.wallet.domain.errors.WalletError
import com.circaso.wallet.domain.models.Allowance
import com.circaso.wallet.domain.models.LimitRule
import com.circaso.wallet.domain.models.Spent
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.repositories.RuleRepository
import com.circaso.wallet.domain.repositories.TransferRepository
import java.math.BigInteger
import java.time.Clock
import java.time.Duration

/**
 * The limit rules (§7a, decided 2026-11-03): rules in the database per owner type (a person, a group, a space), one
 * wallet as an override, and an asset; a cap on one payment's amount and a cap on what leaves the wallet, fees
 * included, over a rolling 24 hours. Payouts to a group's own members count, since every payout is a W4 from the
 * group wallet. The window counts transfers awaiting a signature, submitted or confirmed, so two quick payments can't
 * slip past a cap together; the final check runs under a lock on the wallet ([recheck]).
 */
class Limits(private val rules: RuleRepository, private val transfers: TransferRepository, private val clock: Clock) {
    /**
     * The rule for [owner]'s wallet and [asset] now, or null when no rule limits it. Its caps are micro-dollars whatever
     * the coin (2026-11-07): a payment in another coin counts at its price ([PaymentCoin.usdMinor]).
     */
    suspend fun rule(owner: WalletOwner, asset: Asset): LimitRule? =
        RuleVersions
            .currentLimits(rules.limitRules(), clock.instant())
            .filter { it.matches(owner.type, owner.walletId, asset) }
            .maxWithOrNull(compareBy<LimitRule> { it.specificity }.thenBy { it.effectiveFrom }.thenBy { it.ruleId })

    /** Where [owner]'s wallet stands now; reads the window, so inside a transaction it sees the locked rows. */
    suspend fun allowance(owner: WalletOwner, asset: Asset): Allowance {
        val rule = rule(owner, asset) ?: return Allowance(null, null, 0, DEFAULT_WINDOW, emptyList())
        val now = clock.instant()
        val counted =
            if (rule.dailyMaxMinor == null) {
                emptyList()
            } else {
                transfers
                    .spentSince(owner.holder, now.minus(rule.window))
                    // One still awaiting its signature whose window has closed is about to expire: it can't go now.
                    .filter { !it.awaiting || now.isBefore(it.createdAt.plus(Transfer.SIGNING_WINDOW)) }
                    .map { Spent(it.createdAt, it.usdTotalMinor) }
            }
        return Allowance(
            rule.perPaymentMaxMinor,
            rule.dailyMaxMinor,
            counted.sumOf { it.totalMinor },
            rule.window,
            counted,
        )
    }

    /**
     * Whether a payment worth [amountUsdMinor] with a fee worth [feeUsdMinor] (micro-dollars) fits [allowance]: one
     * payment's amount within its cap, and the total within what is left of the window. [maxAmountMinor] is the most
     * of the payment's coin that would fit, for the error.
     */
    fun check(
        allowance: Allowance,
        amountUsdMinor: Long,
        feeUsdMinor: Long,
        maxAmountMinor: BigInteger,
    ): Resource<Unit> {
        val perPayment = allowance.perPaymentMaxMinor
        if (perPayment != null && amountUsdMinor > perPayment) {
            return Resource.error(
                WalletError.LimitExceeded(
                    details(
                        "limit" to PER_PAYMENT,
                        "limitMinor" to perPayment.toString(),
                        "maxAmountMinor" to maxAmountMinor.toString(),
                    ),
                ),
            )
        }
        val total = amountUsdMinor + feeUsdMinor
        if (allowance.fits(total)) return Resource.success(Unit)
        return Resource.error(WalletError.LimitExceeded(dailyDetails(allowance, total, maxAmountMinor)))
    }

    private fun dailyDetails(
        allowance: Allowance,
        totalMinor: Long,
        maxAmountMinor: BigInteger,
    ): ErrorDetails =
        details(
            "limit" to DAILY,
            "limitMinor" to allowance.dailyMaxMinor.toString(),
            "usedMinor" to allowance.usedMinor.toString(),
            "leftMinor" to (allowance.leftMinor ?: 0).toString(),
            "maxAmountMinor" to maxAmountMinor.toString(),
            "windowHours" to allowance.window.toHours().toString(),
            "resetsAt" to allowance.fitsAt(totalMinor)?.toString(),
            "moreAt" to allowance.moreAt?.toString(),
        )

    companion object {
        const val PER_PAYMENT = "PER_PAYMENT"
        const val DAILY = "DAILY"
        val DEFAULT_WINDOW: Duration = Duration.ofHours(24)

        /** Error details as `{field, problem}` pairs of a name and its value; a null value is left out. */
        fun details(vararg values: Pair<String, String?>): ErrorDetails =
            ErrorDetails(fields = values.mapNotNull { (name, value) -> value?.let { FieldError(name, it) } })
    }
}
