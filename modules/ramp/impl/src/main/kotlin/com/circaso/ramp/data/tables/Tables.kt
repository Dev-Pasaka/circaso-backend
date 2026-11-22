package com.circaso.ramp.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.date
import org.jetbrains.exposed.v1.javatime.timestamp

/** One network's ramp settings; edited by staff, version-checked. */
object SettingsTable : Table("ramp.settings") {
    val network = text("network")
    val state = text("state")
    val chains = text("chains")
    val orderMinMinor = long("order_min_minor")
    val orderMaxMinor = long("order_max_minor")
    val dailyLimitMinor = long("daily_limit_minor")
    val promptsPerHour = integer("prompts_per_hour")
    val feeBps = integer("fee_bps")
    val merchantShareBps = integer("merchant_share_bps")
    val feeSettleHourUtc = integer("fee_settle_hour_utc")
    val quoteSeconds = integer("quote_seconds")
    val paymentWindowSeconds = integer("payment_window_seconds")
    val kycRequired = bool("kyc_required")
    val callbackBaseUrl = text("callback_base_url")
    val walletSetId = text("wallet_set_id").nullable()
    val updatedBy = text("updated_by").nullable()
    val updatedAt = timestamp("updated_at")
    val version = long("version").default(0)
    override val primaryKey = PrimaryKey(network)
}

/** Who may trade while a network's ramp is in PREVIEW. */
object PreviewAccessTable : Table("ramp.preview_access") {
    val userId = javaUUID("user_id")
    val network = text("network")
    val grantedBy = text("granted_by").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(userId, network)
}

/** A partner merchant. The row is also the lock for everything about the merchant's float. */
object MerchantsTable : Table("ramp.merchants") {
    val merchantId = javaUUID("merchant_id")
    val contactEmail = text("contact_email")
    val displayName = text("display_name").default("")
    val contactPhone = text("contact_phone").default("")
    val state = text("state")
    val callbackKey = text("callback_key").nullable()
    val onlineUntil = timestamp("online_until").nullable()
    val payoutMode = text("payout_mode").default("NONE")
    val lastAssignedAt = timestamp("last_assigned_at").nullable()
    val approvedBy = text("approved_by").nullable()
    val approvedAt = timestamp("approved_at").nullable()
    val createdAt = timestamp("created_at")
    val version = long("version").default(0)
    override val primaryKey = PrimaryKey(merchantId)
}

/** Portal sign-ins by the SHA-256 of their token; `expires_at` is filtered on every read and purged. */
object MerchantSessionsTable : Table("ramp.merchant_sessions") {
    val tokenHash = binary("token_hash")
    val merchantId = javaUUID("merchant_id")
    val createdAt = timestamp("created_at")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(tokenHash)
}

/** A merchant's float wallet, one per network. */
object MerchantWalletsTable : Table("ramp.merchant_wallets") {
    val merchantId = javaUUID("merchant_id")
    val network = text("network")
    val blockchain = text("blockchain")
    val custodyWalletId = text("custody_wallet_id")
    val walletAddress = text("wallet_address")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(merchantId, network)
}

/** Circaso's own ramp wallet, one per network. */
object RevenueWalletsTable : Table("ramp.revenue_wallets") {
    val network = text("network")
    val blockchain = text("blockchain")
    val custodyWalletId = text("custody_wallet_id")
    val walletAddress = text("wallet_address")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(network)
}

/** A merchant's sealed payment credentials: never plaintext. */
object MerchantCredentialsTable : Table("ramp.merchant_credentials") {
    val merchantId = javaUUID("merchant_id")
    val provider = text("provider")
    val channelId = text("channel_id")
    val sealed = binary("sealed")
    val wrappedKey = binary("wrapped_key")
    val pinSalt = binary("pin_salt")
    val kdfParams = text("kdf_params")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(merchantId)
}

/** A price held for a minute; `expires_at` is filtered on every read and purged. */
object QuotesTable : Table("ramp.quotes") {
    val quoteId = javaUUID("quote_id")
    val userId = javaUUID("user_id")
    val network = text("network")
    val direction = text("direction")
    val blockchain = text("blockchain")
    val fiatCurrency = text("fiat_currency")
    val fiatAmountMinor = long("fiat_amount_minor")
    val asset = text("asset")
    val assetAmountMinor = long("asset_amount_minor")
    val rate = text("rate")
    val feeMinor = long("fee_minor")
    val feeBps = integer("fee_bps")
    val merchantShareBps = integer("merchant_share_bps")
    val expiresAt = timestamp("expires_at")
    override val primaryKey = PrimaryKey(quoteId)
}

/** One trade; its state moves only by the transition guard. */
object OrdersTable : Table("ramp.orders") {
    val orderId = javaUUID("order_id")
    val userId = javaUUID("user_id")
    val merchantId = javaUUID("merchant_id")
    val quoteId = javaUUID("quote_id")
    val network = text("network")
    val direction = text("direction")
    val state = text("state")
    val blockchain = text("blockchain")
    val fiatCurrency = text("fiat_currency")
    val fiatAmountMinor = long("fiat_amount_minor")
    val asset = text("asset")
    val assetAmountMinor = long("asset_amount_minor")
    val rate = text("rate")
    val feeMinor = long("fee_minor")
    val feeBps = integer("fee_bps")
    val merchantShareBps = integer("merchant_share_bps")
    val payerPhone = text("payer_phone")
    val destinationAddress = text("destination_address")
    val providerReference = text("provider_reference").nullable()
    val checkoutId = text("checkout_id").nullable()
    val mpesaReceipt = text("mpesa_receipt").nullable()
    val custodyTxId = text("custody_tx_id").nullable()
    val txHash = text("tx_hash").nullable()
    val failureReason = text("failure_reason").nullable()
    val paymentDeadline = timestamp("payment_deadline")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(orderId)
}

/** The part of a merchant's float promised to an open order. */
object ReservationsTable : Table("ramp.reservations") {
    val orderId = javaUUID("order_id")
    val merchantId = javaUUID("merchant_id")
    val network = text("network")
    val amountMinor = long("amount_minor")
    val asset = text("asset")
    val kind = text("kind")
    val state = text("state")
    val createdAt = timestamp("created_at")
    val closedAt = timestamp("closed_at").nullable()
    override val primaryKey = PrimaryKey(orderId)
}

/** What a completed order earned its merchant and Circaso, and the sweep that collected Circaso's part. */
object CommissionsTable : Table("ramp.commissions") {
    val orderId = javaUUID("order_id")
    val merchantId = javaUUID("merchant_id")
    val network = text("network")
    val merchantMinor = long("merchant_minor")
    val platformMinor = long("platform_minor")
    val platformAssetMinor = long("platform_asset_minor")
    val settlementId = javaUUID("settlement_id").nullable()
    val settledAt = timestamp("settled_at").nullable()
    val currency = text("currency")
    val earnedAt = timestamp("earned_at")
    override val primaryKey = PrimaryKey(orderId)
}

/** One daily sweep of what a merchant's float owes Circaso. */
object FeeSettlementsTable : Table("ramp.fee_settlements") {
    val settlementId = javaUUID("settlement_id")
    val merchantId = javaUUID("merchant_id")
    val network = text("network")
    val day = date("day")
    val amountMinor = long("amount_minor")
    val orders = integer("orders")
    val state = text("state")
    val custodyTxId = text("custody_tx_id").nullable()
    val txHash = text("tx_hash").nullable()
    val failureReason = text("failure_reason").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(settlementId)
}

/** The frames the ramp sent, kept for replay; daily partitions made and dropped by `ramp.purge-expired`. */
object RealtimeTable : Table("ramp.realtime") {
    val userId = javaUUID("user_id")
    val seq = long("seq")
    val frame = text("frame")
    val sentAt = timestamp("sent_at")
    override val primaryKey = PrimaryKey(userId, seq, sentAt)
}
