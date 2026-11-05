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
import com.circaso.wallet.domain.models.LimitRule
import com.circaso.wallet.domain.models.NoFeeReason
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.ports.FeeConfig
import com.circaso.wallet.domain.ports.WalletWarnings
import com.circaso.wallet.domain.repositories.RuleRepository
import com.circaso.wallet.domain.repositories.WalletOwnerRepository
import com.circaso.wallet.domain.repositories.WalletRepository
import java.math.BigDecimal
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
 * How one payment is priced (§7b): the rule that applies, the network cost estimated for the exact call that will
 * run, and where the fee goes. [feeFor] gives the fee for any amount of it, so the most that fits a balance can be
 * worked out without asking Circle again.
 */
class Pricing(
    val parties: FeeParties,
    val rule: FeeRule?,
    val gasMinor: Long?,
    val feeAddress: String?,
    val reason: NoFeeReason?,
) {
    /**
     * `fee = max(rate × min(amount, cap), min_fee_usd, gas × (1 + margin) ÷ share)`; the rate part rounded half up to
     * the cent, the floors up to the cent, so the network cost always stays within its share of the fee. A 0 % rule
     * (a promotion) takes nothing and adds no floor: its gas is booked as promotion cost.
     */
    fun feeFor(amountMinor: Long): Fee {
        val applied = rule
        if (applied == null || reason != null || applied.rateBps == 0 || feeAddress == null) {
            return Fee(
                0,
                applied?.rateBps ?: 0,
                false,
                applied?.ruleId,
                applied?.version,
                gasMinor,
                share(),
                parties,
                null,
                reason,
            )
        }
        val base = BigDecimal.valueOf(minOf(amountMinor, applied.capBaseMinor))
        val rated = cents(base * BigDecimal.valueOf(applied.rateBps.toLong()) / BPS, RoundingMode.HALF_UP)
        val gasFloor =
            gasMinor?.let { gas ->
                cents(
                    BigDecimal.valueOf(gas) * BigDecimal.valueOf(BPS_LONG + applied.gasMarginBps) /
                        BigDecimal.valueOf(applied.gasShareMaxBps.toLong()),
                    RoundingMode.CEILING,
                )
            } ?: 0
        val floor =
            maxOf(
                gasFloor,
                applied.minFeeMinor?.let { cents(BigDecimal.valueOf(it), RoundingMode.CEILING) } ?: 0,
            )
        val fee = maxOf(rated, floor)
        return Fee(
            fee,
            applied.rateBps,
            fee > rated,
            applied.ruleId,
            applied.version,
            gasMinor,
            share(),
            parties,
            feeAddress,
        )
    }

    /**
     * The largest amount whose total (the amount and its fee) fits [budgetMinor] and that is at most [capMinor]; 0
     * when none does. The total only grows with the amount, so a binary search finds it.
     */
    fun maxAmount(budgetMinor: Long, capMinor: Long? = null): Long {
        var low = 0L
        var high = minOf(budgetMinor, capMinor ?: Long.MAX_VALUE)
        if (high <= 0) return 0
        while (low < high) {
            val mid = low + (high - low + 1) / 2
            if (mid + feeFor(mid).amountMinor <= budgetMinor) low = mid else high = mid - 1
        }
        return low
    }

    private fun share(): Int = rule?.gasShareMaxBps ?: DEFAULT_SHARE_BPS

    private fun cents(value: BigDecimal, rounding: RoundingMode): Long =
        value.divide(CENT, 0, rounding).multiply(CENT).toLong()

    private companion object {
        const val BPS_LONG = 10_000L
        val BPS: BigDecimal = BigDecimal.valueOf(BPS_LONG)

        /** One cent in USDC minor units. */
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
) {
    private val estimates = ConcurrentHashMap<String, Pair<NetworkCost, Instant>>()

    /** The pricing of [amount] from [owner]'s wallet [sender] to [destination]; an error when gas can't be estimated. */
    suspend fun pricing(
        owner: WalletOwner,
        sender: Sender,
        destination: Destination,
        amount: Money,
    ): Resource<Pricing> {
        val chain = sender.wallet.blockchain
        val parties = parties(owner, destination, chain, amount.asset)
        val rule =
            RuleVersions
                .currentFees(rules.feeRules(), clock.instant())
                .filter { it.matches(parties) }
                .maxWithOrNull(compareBy<FeeRule> { it.specificity }.thenBy { it.effectiveFrom }.thenBy { it.ruleId })
                ?: return Resource.success(Pricing(parties, null, null, null, NoFeeReason.NO_RULE))
        if (rule.rateBps == 0) return Resource.success(Pricing(parties, rule, null, null, null))
        if (!custody.canSplit(chain)) return untaken(parties, rule, NoFeeReason.NO_SPLIT)
        val feeAddress = config.feeAddress(chain)?.let { Addresses.normalise(it) }
        if (feeAddress.isNullOrEmpty()) return untaken(parties, rule, NoFeeReason.NO_FEE_WALLET)
        val gas =
            when (val estimated = gasOf(sender, destination, feeAddress)) {
                is Resource.Success -> estimated.data
                is Resource.Error -> return estimated
            }
        return Resource.success(Pricing(parties, rule, gas, feeAddress, null))
    }

    private fun untaken(
        parties: FeeParties,
        rule: FeeRule,
        reason: NoFeeReason,
    ): Resource<Pricing> {
        warnings.feeNotTaken(parties.blockchain, reason.name)
        return Resource.success(Pricing(parties, rule, null, null, reason))
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
    ): Resource<Long> {
        val chain = sender.wallet.blockchain
        val key = "${sender.wallet.circleWalletId}|$chain|${destination.address}"
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
                                PaymentLeg(destination.address, Money(1, Asset.USDC)),
                                PaymentLeg(feeAddress, Money(1, Asset.USDC)),
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
