package com.circaso.wallet.domain.support

import com.circaso.integrations.api.NetworkCost
import com.circaso.integrations.api.PaymentLeg
import com.circaso.integrations.api.SplitTransferRequest
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.api.WalletOwnerType
import com.circaso.wallet.domain.models.Fee
import com.circaso.wallet.domain.models.FeeKind
import com.circaso.wallet.domain.models.FeeParties
import com.circaso.wallet.domain.models.FeeRule
import com.circaso.wallet.domain.models.GasSource
import com.circaso.wallet.domain.models.LimitRule
import com.circaso.wallet.domain.models.NoFeeReason
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.ports.FeeConfig
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.RuleRepository
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import com.circaso.wallet.domain.repositories.WalletRepository
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** The versions of rules in force: for each rule its latest version that has started, if that one hasn't ended. */
object RuleVersions {
    fun currentFees(all: List<FeeRule>, at: Instant): List<FeeRule> =
        all
            .filter { !it.effectiveFrom.isAfter(at) }
            .groupBy { it.ruleId }
            .mapNotNull { (_, versions) -> versions.maxByOrNull { it.version }?.takeIf { it.inForce(at) } }

    fun currentLimits(all: List<LimitRule>, at: Instant): List<LimitRule> =
        all
            .filter { !it.effectiveFrom.isAfter(at) }
            .groupBy { it.ruleId }
            .mapNotNull { (_, versions) -> versions.maxByOrNull { it.version }?.takeIf { it.inForce(at) } }
}

/**
 * How one payment is priced (§7b): the rule that applies, the network cost it is priced on ([gasMinor], from
 * [gasSource]), where the fee goes, and the [coin] it is paid in. [feeFor] gives the fee for any amount of it, so the
 * most that fits a balance can be worked out without asking Circle again.
 */
class Pricing(
    val parties: FeeParties,
    val rule: FeeRule?,
    val gasMinor: Long?,
    val feeAddress: String?,
    val reason: NoFeeReason?,
    /** The coin it is paid in; a dollar stablecoin unless said (the fee is then the dollar fee itself). */
    val coin: PaymentCoin = PaymentCoin.dollar(parties.asset),
    val gasSource: GasSource? = null,
) {
    /**
     * Gas inclusive (decision 2026-11-08): `fee = ceil_cent(gas × (1 + margin)) + half_up_cent(rate × min(amount,
     * cap))`, raised to `min_fee_usd` when the rule sets one, in dollars. The network cost always passes through, so a
     * 0 % rule charges the network cost alone; Circaso no longer absorbs gas. Without a network cost (no rule, no fee
     * leg on the chain, no fee wallet) nothing is charged, as before.
     *
     * [amountMinor] is in the [coin]; the dollar fee is worked out on its dollar value and charged in the coin,
     * converted at the coin's price and rounded up to its minor unit (decision 2026-11-07). For USDC both steps are
     * the identity.
     */
    fun feeFor(amountMinor: BigInteger): Fee {
        val applied = rule
        val gas = gasMinor
        if (applied == null || reason != null || feeAddress == null || gas == null) {
            return Fee(
                BigInteger.ZERO,
                applied?.rateBps ?: 0,
                false,
                applied?.ruleId,
                applied?.version,
                gasMinor,
                share(),
                parties,
                null,
                reason,
                usdMinor = 0,
                usdPrice = coin.usdPrice,
                gasSource = gasSource,
            )
        }
        val network = networkPart(applied, gas)
        val base = BigDecimal.valueOf(minOf(coin.usdMinor(amountMinor), applied.capBaseMinor))
        val rated = cents((base * BigDecimal.valueOf(applied.rateBps.toLong())).divide(BPS), RoundingMode.HALF_UP)
        val priced = network + rated
        val floor = applied.minFeeMinor?.let { cents(BigDecimal.valueOf(it), RoundingMode.CEILING) } ?: 0
        val fee = maxOf(priced, floor)
        return Fee(
            coin.minorOf(fee),
            applied.rateBps,
            fee > priced,
            applied.ruleId,
            applied.version,
            gasMinor,
            share(),
            parties,
            feeAddress,
            usdMinor = fee,
            usdPrice = coin.usdPrice,
            gasSource = gasSource,
        )
    }

    /** The network cost with the rule's margin on top, rounded up to the cent, in micro-dollars. */
    private fun networkPart(rule: FeeRule, gas: Long): Long =
        cents(
            (BigDecimal.valueOf(gas) * BigDecimal.valueOf(BPS_LONG + rule.gasMarginBps)).divide(BPS),
            RoundingMode.CEILING,
        )

    fun feeFor(amountMinor: Long): Fee = feeFor(BigInteger.valueOf(amountMinor))

    /**
     * The largest amount (in the coin) whose total, the amount and its fee, fits [budgetMinor] of the coin, and that
     * is at most [capUsdMinor] (one payment's cap) and, with its fee, at most [leftUsdMinor] (what is left of the
     * window), both in micro-dollars; 0 when none does. The total only grows with the amount, so a binary search finds
     * it. For USDC this is the amount within `min(budget, left)` and the cap, as before.
     */
    fun maxAmount(
        budgetMinor: BigInteger,
        capUsdMinor: Long? = null,
        leftUsdMinor: Long? = null,
    ): BigInteger {
        if (budgetMinor.signum() <= 0) return BigInteger.ZERO

        fun fits(amount: BigInteger): Boolean {
            if (capUsdMinor != null && coin.usdMinor(amount) > capUsdMinor) return false
            val fee = feeFor(amount)
            if (amount + fee.amountMinor > budgetMinor) return false
            return leftUsdMinor == null || coin.usdMinor(amount) + fee.usdMinor <= leftUsdMinor
        }
        var low = BigInteger.ZERO
        var high = budgetMinor
        while (low < high) {
            val mid = low + (high - low + BigInteger.ONE).shiftRight(1)
            if (fits(mid)) low = mid else high = mid - BigInteger.ONE
        }
        return low
    }

    /** The legacy form, all in one dollar stablecoin's minor units (the budget already within what is left). */
    fun maxAmount(budgetMinor: Long, capMinor: Long? = null): Long =
        maxAmount(BigInteger.valueOf(budgetMinor), capMinor).toLong()

    private fun share(): Int = rule?.gasShareMaxBps ?: DEFAULT_SHARE_BPS

    private fun cents(value: BigDecimal, rounding: RoundingMode): Long =
        value.divide(CENT, 0, rounding).multiply(CENT).toLong()

    /** This pricing in another coin's price, the rest unchanged. */
    fun withCoin(coin: PaymentCoin): Pricing = Pricing(parties, rule, gasMinor, feeAddress, reason, coin, gasSource)

    private companion object {
        const val BPS_LONG = 10_000L
        val BPS: BigDecimal = BigDecimal.valueOf(BPS_LONG)

        /** One cent in micro-dollars. */
        val CENT: BigDecimal = BigDecimal.valueOf(10_000)
        const val DEFAULT_SHARE_BPS = 2_500
    }
}

/**
 * The fee side of a payment (§7b): which rule applies, and the network cost of the exact call that will run. On a
 * chain that can split (EVM: the batch on the sender's own wallet) the fee travels in the same signature as the
 * payment; elsewhere (Solana, until a second spike) and where no fee wallet is configured, nothing is charged.
 * Estimates are kept for a minute per sending wallet and payee, so a person typing an amount costs Circle one call.
 */
class FeeCalculator(
    private val rules: RuleRepository,
    private val custody: StablecoinCustody,
    private val config: FeeConfig,
    private val wallets: WalletRepository,
    private val owners: WalletOwnerRepository,
    private val warnings: WalletWarnings,
    private val clock: Clock,
    /** What recent transfers actually cost; without it (tests) every payment is priced on Circle's estimate. */
    private val networkCosts: NetworkCosts? = null,
) {
    private val estimates = ConcurrentHashMap<String, Pair<NetworkCost, Instant>>()

    /**
     * The pricing of a payment from [owner]'s wallet [sender] to [destination]; an error when gas can't be estimated.
     * Circle is still asked for the exact call (it is what checks the call can run), but the network cost is priced
     * on what the chain's recent transfers cost when there is enough history ([NetworkCosts]).
     */
    suspend fun pricing(
        owner: WalletOwner,
        sender: Sender,
        destination: Destination,
        coin: PaymentCoin,
    ): Resource<Pricing> {
        val chain = sender.wallet.blockchain
        val parties = parties(owner, destination, chain, coin.asset)
        val rule =
            RuleVersions
                .currentFees(rules.feeRules(), clock.instant())
                .filter { it.matches(parties) }
                .maxWithOrNull(compareBy<FeeRule> { it.specificity }.thenBy { it.effectiveFrom }.thenBy { it.ruleId })
                ?: return Resource.success(Pricing(parties, null, null, null, NoFeeReason.NO_RULE, coin))
        // A 0 % rule where no fee leg can be carried (Solana) charges nothing, quietly, as it always has.
        val free = rule.rateBps == 0 && rule.minFeeMinor == null
        if (!custody.canSplit(chain)) return untaken(parties, rule, NoFeeReason.NO_SPLIT, coin, quiet = free)
        val feeAddress = config.feeAddress(chain)?.let { Addresses.normalise(it) }
        if (feeAddress.isNullOrEmpty()) return untaken(parties, rule, NoFeeReason.NO_FEE_WALLET, coin, quiet = free)
        val estimate =
            when (val estimated = gasOf(sender, destination, feeAddress, coin)) {
                is Resource.Success -> estimated.data
                is Resource.Error -> return estimated
            }
        val recent = networkCosts?.recent(chain, coin.asset.symbol)
        val (gas, source) = if (recent != null) recent.gasMinor to GasSource.RECENT else estimate to GasSource.ESTIMATE
        return Resource.success(Pricing(parties, rule, gas, feeAddress, null, coin, source))
    }

    /** The legacy form for a dollar stablecoin offered on the chain. */
    suspend fun pricing(
        owner: WalletOwner,
        sender: Sender,
        destination: Destination,
        amount: Money,
    ): Resource<Pricing> = pricing(owner, sender, destination, PaymentCoin.dollar(amount.asset))

    private fun untaken(
        parties: FeeParties,
        rule: FeeRule,
        reason: NoFeeReason,
        coin: PaymentCoin,
        quiet: Boolean,
    ): Resource<Pricing> {
        if (quiet) return Resource.success(Pricing(parties, rule, null, null, null, coin))
        warnings.feeNotTaken(parties.blockchain, reason.name)
        return Resource.success(Pricing(parties, rule, null, null, reason, coin))
    }

    /**
     * Who is on either side. A Circaso wallet paid by its address is still internal: whose it is decides the rate,
     * not how the person typed it.
     */
    private suspend fun parties(
        owner: WalletOwner,
        destination: Destination,
        blockchain: String,
        asset: Asset,
    ): FeeParties {
        val receiver: WalletOwnerType? =
            when {
                destination.toGroupId != null -> {
                    WalletOwnerType.GROUP
                }

                destination.recipientId != null -> {
                    owners.find(WalletId(destination.recipientId.value))?.type ?: WalletOwnerType.PERSON
                }

                else -> {
                    wallets.walletAt(destination.address, blockchain)?.let { ours ->
                        owners.find(WalletId(ours.userId.value))?.type ?: WalletOwnerType.PERSON
                    }
                }
            }
        val kind = if (receiver == null) FeeKind.EXTERNAL else FeeKind.INTERNAL
        return FeeParties(kind, owner.type, receiver, blockchain, asset, owner.walletId)
    }

    /**
     * The network cost of the batch that will run (payee and fee wallet), in micro-dollars. It is estimated with one
     * minor unit on each leg: gas doesn't follow the amounts, and the estimate then holds whatever the person types.
     */
    private suspend fun gasOf(
        sender: Sender,
        destination: Destination,
        feeAddress: String,
        coin: PaymentCoin,
    ): Resource<Long> {
        val chain = sender.wallet.blockchain
        val coinKey = "${coin.asset.symbol}|${coin.tokenAddress}"
        val key = "${sender.wallet.circleWalletId}|$chain|${destination.address}|$coinKey"
        val now = clock.instant()
        val kept = estimates[key]?.takeIf { (_, at) -> Duration.between(at, now) < ESTIMATE_LIFE }?.first
        val cost =
            kept ?: run {
                val request =
                    SplitTransferRequest(
                        blockchain = chain,
                        walletId = sender.wallet.circleWalletId,
                        walletAddress = sender.wallet.address,
                        legs =
                            listOf(
                                PaymentLeg(destination.address, Money(1, coin.asset), coin.tokenAddress),
                                PaymentLeg(feeAddress, Money(1, coin.asset), coin.tokenAddress),
                            ),
                        idempotencyKey = UUID.randomUUID(),
                        refId = "",
                    )
                when (val estimated = custody.estimateSplit(sender.user.circleUserId.toString(), request)) {
                    is Resource.Success -> estimated.data.also { estimates[key] = it to now }
                    is Resource.Error -> return estimated.orTransferError() as Resource.Error
                }
            }
        val dollars = cost.amount * config.nativeUsd(chain)
        return Resource.success(dollars.movePointRight(MICRO).setScale(0, RoundingMode.CEILING).toLong())
    }

    private companion object {
        val ESTIMATE_LIFE: Duration = Duration.ofMinutes(1)

        /** Micro-dollars: USDC's minor units. */
        const val MICRO = 6
    }
}
