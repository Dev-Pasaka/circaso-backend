package com.circaso.wallet.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** The bounds every fee rate stays within (one row); the trigger `fee_rules_in_bounds` in the migration enforces them. */
object FeeBoundsTable : Table("wallet.fee_bounds") {
    val id = short("id")
    val minRateBps = integer("min_rate_bps")
    val maxRateBps = integer("max_rate_bps")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

/** Every version of every fee rule (phase 2F, §7b); a change is a new version, nothing is overwritten. */
object FeeRulesTable : Table("wallet.fee_rules") {
    val ruleId = javaUUID("rule_id")
    val version = integer("version")
    val kind = text("kind").nullable()
    val senderType = text("sender_type").nullable()
    val receiverType = text("receiver_type").nullable()
    val blockchain = text("blockchain").nullable()
    val asset = text("asset").nullable()
    val walletId = javaUUID("wallet_id").nullable()
    val rateBps = integer("rate_bps")
    val capBaseUsd = decimal("cap_base_usd", 20, 6).default(java.math.BigDecimal(10_000))
    val minFeeUsd = decimal("min_fee_usd", 20, 6).nullable()
    val gasShareMaxBps = integer("gas_share_max_bps").default(2_500)
    val gasMarginBps = integer("gas_margin_bps").default(2_000)
    val effectiveFrom = timestamp("effective_from")
    val effectiveTo = timestamp("effective_to").nullable()
    val note = text("note").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(ruleId, version)
}

/** Every version of every limit rule (phase 2F, §7a). */
object LimitRulesTable : Table("wallet.limit_rules") {
    val ruleId = javaUUID("rule_id")
    val version = integer("version")
    val ownerType = text("owner_type").nullable()
    val walletId = javaUUID("wallet_id").nullable()
    val asset = text("asset").nullable()
    val perPaymentMaxUsd = decimal("per_payment_max_usd", 20, 6).nullable()
    val dailyMaxUsd = decimal("daily_max_usd", 20, 6).nullable()
    val windowHours = integer("window_hours").default(24)
    val effectiveFrom = timestamp("effective_from")
    val effectiveTo = timestamp("effective_to").nullable()
    val note = text("note").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(ruleId, version)
}

/** The fee shown to a person by W17, held for the signing window; purged once it has run out. */
object FeeQuotesTable : Table("wallet.fee_quotes") {
    val quoteId = javaUUID("quote_id")
    val holderId = javaUUID("holder_id")
    val signerId = javaUUID("signer_id")
    val blockchain = text("blockchain")
    val payeeKey = text("payee_key")
    val amountMinor = decimal("amount_minor", 78, 0)
    val asset = text("asset")
    val assetDecimals = short("asset_decimals").default(6)
    val feeMinor = decimal("fee_minor", 78, 0)
    val feeUsdMinor = long("fee_usd_minor")
    val usdPrice = decimal("usd_price", 38, 18).default(java.math.BigDecimal.ONE)
    val rateBps = integer("rate_bps")
    val minimum = bool("minimum")
    val ruleId = javaUUID("rule_id").nullable()
    val ruleVersion = integer("rule_version").nullable()
    val gasMinor = long("gas_minor").nullable()
    val gasShareMaxBps = integer("gas_share_max_bps")
    val kind = text("kind")
    val senderType = text("sender_type")
    val receiverType = text("receiver_type").nullable()
    val feeAddress = text("fee_address").nullable()
    val noFeeReason = text("no_fee_reason").nullable()
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(quoteId)

    init {
        index("fee_quotes_expires_idx", false, expiresAt)
    }
}

/** One row per transfer: the fee it carries, under which rule, and how it ended (§7b "Fee address and accounting"). */
object FeeLedgerTable : Table("wallet.fee_ledger") {
    val transferId = javaUUID("transfer_id")
    val holderId = javaUUID("holder_id")
    val blockchain = text("blockchain")
    val asset = text("asset")
    val assetDecimals = short("asset_decimals").default(6)
    val amountMinor = decimal("amount_minor", 78, 0)
    val feeMinor = decimal("fee_minor", 78, 0)
    val feeUsdMinor = long("fee_usd_minor")
    val usdPrice = decimal("usd_price", 38, 18).default(java.math.BigDecimal.ONE)
    val rateBps = integer("rate_bps")
    val minimum = bool("minimum")
    val ruleId = javaUUID("rule_id").nullable()
    val ruleVersion = integer("rule_version").nullable()
    val kind = text("kind")
    val senderType = text("sender_type")
    val receiverType = text("receiver_type").nullable()
    val feeAddress = text("fee_address").nullable()
    val noFeeReason = text("no_fee_reason").nullable()
    val gasEstimateMinor = long("gas_estimate_minor").nullable()
    val gasShareMaxBps = integer("gas_share_max_bps")
    val state = text("state")
    val txHash = text("tx_hash").nullable()
    val actualGasNative = text("actual_gas_native").nullable()
    val actualGasMinor = long("actual_gas_minor").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(transferId)

    init {
        index("fee_ledger_report_idx", false, createdAt, blockchain)
    }
}
