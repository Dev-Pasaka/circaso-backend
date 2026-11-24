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
    val collectionsProvider = text("collections_provider")
    val providerFeeFloorMinor = long("provider_fee_floor_minor")
    val kycProvider = text("kyc_provider")
    val kycEnvironment = text("kyc_environment")
    val kycWorkflowUser = text("kyc_workflow_user")
    val kycWorkflowMerchant = text("kyc_workflow_merchant")
    val merchantPortalUrl = text("merchant_portal_url")
    val kycAttemptsPerDay = integer("kyc_attempts_per_day")
    val kycPendingHours = integer("kyc_pending_hours")
    val offrampState = text("offramp_state").default("OFF")
    val offrampMinMinor = long("offramp_min_minor").default(1_000)
    val offrampMaxMinor = long("offramp_max_minor").default(500_000)
    val offrampDailyLimitMinor = long("offramp_daily_limit_minor").default(2_000_000)
    val transferWindowSeconds = integer("transfer_window_seconds").default(900)
    val payoutWindowSeconds = integer("payout_window_seconds").default(1_800)
    val payoutFeeBands = text("payout_fee_bands").default("[]")
    val payoutBalanceLowMinor = long("payout_balance_low_minor").default(100_000)
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
    val paymentsIssue = text("payments_issue").nullable()
    val paymentsIssueAt = timestamp("payments_issue_at").nullable()
    val merchantType = text("merchant_type").default("INDIVIDUAL")
    val orderLimitMinor = long("order_limit_minor").nullable()
    val offersOnramp = bool("offers_onramp").default(true)
    val offersOfframp = bool("offers_offramp").default(false)
    val payoutAccountId = text("payout_account_id").nullable()
    val payoutIssue = text("payout_issue").nullable()
    val payoutIssueAt = timestamp("payout_issue_at").nullable()

    // FinoQ's payment wallet (what payouts spend).
    val payoutBalanceMinor = long("payout_balance_minor").nullable()
    val payoutBalanceSource = text("payout_balance_source").nullable()
    val payoutBalanceReadAt = timestamp("payout_balance_read_at").nullable()

    // PayHero's service wallet (buying's prompt fees) and FinoQ's (selling's payout fees), V11.
    val payheroServiceMinor = long("payhero_service_balance_minor").nullable()
    val payheroServiceSource = text("payhero_service_balance_source").nullable()
    val payheroServiceReadAt = timestamp("payhero_service_read_at").nullable()
    val finoqServiceMinor = long("finoq_service_balance_minor").nullable()
    val finoqServiceSource = text("finoq_service_balance_source").nullable()
    val finoqServiceReadAt = timestamp("finoq_service_read_at").nullable()
    val balancesReadAt = timestamp("balances_read_at").nullable()
    val webhookSecretSealed = binary("webhook_secret_sealed").nullable()
    val webhookSecretUpdatedAt = timestamp("webhook_secret_updated_at").nullable()
    val onlineOpenEnded = bool("online_open_ended").default(false)
    val onlineSince = timestamp("online_since").nullable()
    val offlineReason = text("offline_reason").nullable()
    val offlineAt = timestamp("offline_at").nullable()
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
    val providerFeeMinor = long("provider_fee_minor").default(0)
    val providerFeeSource = text("provider_fee_source").nullable()
    val rateSource = text("rate_source").nullable()
    val referenceRate = text("reference_rate").nullable()
    val spreadBps = integer("spread_bps").nullable()
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
    val collectionsProvider = text("collections_provider")
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
    val attempt = integer("attempt").default(1)
    val providerFeeMinor = long("provider_fee_minor").default(0)
    val providerFeeSource = text("provider_fee_source").nullable()
    val providerFeeActualMinor = long("provider_fee_actual_minor").nullable()
    val walletTransferId = javaUUID("wallet_transfer_id").nullable()
    val fundedMinor = long("funded_minor").nullable()
    val fundingTxHash = text("funding_tx_hash").nullable()
    val fundedAt = timestamp("funded_at").nullable()
    val rateSource = text("rate_source").nullable()
    val referenceRate = text("reference_rate").nullable()
    val spreadBps = integer("spread_bps").nullable()
    val refundAddress = text("refund_address").nullable()
    val payoutAttempts = integer("payout_attempts").default(0)
    val payoutFailures = integer("payout_failures").default(0)
    val payoutReference = text("payout_reference").nullable()
    val payoutProviderReference = text("payout_provider_reference").nullable()
    val payoutSecondaryReference = text("payout_secondary_reference").nullable()
    val payoutOutcome = text("payout_outcome").nullable()
    val payoutSentAt = timestamp("payout_sent_at").nullable()
    val payoutRetryAt = timestamp("payout_retry_at").nullable()
    val recheckRequestedAt = timestamp("recheck_requested_at").nullable()
    override val primaryKey = PrimaryKey(orderId)
}

object PayoutHoldsTable : Table("ramp.payout_holds") {
    val orderId = javaUUID("order_id")
    val merchantId = javaUUID("merchant_id")
    val amountMinor = long("amount_minor")
    val currency = text("currency")
    val state = text("state")
    val createdAt = timestamp("created_at")
    val closedAt = timestamp("closed_at").nullable()
    override val primaryKey = PrimaryKey(orderId)
}

object TestPayoutsTable : Table("ramp.test_payouts") {
    val testId = javaUUID("test_id")
    val merchantId = javaUUID("merchant_id")
    val provider = text("provider")
    val amountMinor = long("amount_minor")
    val payeePhone = text("payee_phone")
    val state = text("state")
    val outcome = text("outcome").nullable()
    val providerReference = text("provider_reference").nullable()
    val secondaryReference = text("secondary_reference").nullable()
    val receipt = text("receipt").nullable()
    val paidAmountMinor = long("paid_amount_minor").nullable()
    val providerFeeMinor = long("provider_fee_minor").nullable()
    val providerFeeSource = text("provider_fee_source").nullable()
    val balanceBeforeMinor = long("balance_before_minor").nullable()
    val balanceBeforeSource = text("balance_before_source").nullable()
    val balanceAfterMinor = long("balance_after_minor").nullable()
    val balanceAfterSource = text("balance_after_source").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(testId)
}

/** A merchant's top-ups of their own wallets at the provider (2026-11-23). */
object TopUpsTable : Table("ramp.topups") {
    val topUpId = javaUUID("topup_id")
    val merchantId = javaUUID("merchant_id")
    val wallet = text("wallet")
    val provider = text("provider")
    val amountMinor = long("amount_minor")
    val payerPhone = text("payer_phone")
    val state = text("state")
    val failure = text("failure").nullable()
    val outcome = text("outcome").nullable()
    val providerReference = text("provider_reference").nullable()
    val checkoutId = text("checkout_id").nullable()
    val receipt = text("receipt").nullable()
    val confirmedBy = text("confirmed_by").nullable()
    val balanceBeforeMinor = long("balance_before_minor").nullable()
    val balanceAfterMinor = long("balance_after_minor").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    val closedAt = timestamp("closed_at").nullable()
    override val primaryKey = PrimaryKey(topUpId)
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

/**
 * Who passed KYC for the ramp: one row per subject and provider environment, with the provider's current session and,
 * once verified live, the identity key. Never a document.
 */
object VerificationsTable : Table("ramp.verifications") {
    val subjectKind = text("subject_kind")
    val subjectId = javaUUID("subject_id")
    val environment = text("environment")
    val status = text("status")
    val reason = text("reason").nullable()
    val submitted = bool("submitted").default(false)
    val provider = text("provider")
    val sessionId = text("session_id").nullable()
    val sessionToken = text("session_token").nullable()
    val sessionUrl = text("session_url").nullable()
    val sessionExpiresAt = timestamp("session_expires_at").nullable()
    val identityKey = text("identity_key").nullable()
    val attempts = integer("attempts").default(0)
    val attemptDay = date("attempt_day").nullable()
    val attemptsOnDay = integer("attempts_on_day").default(0)
    val startedAt = timestamp("started_at").nullable()
    val checkedAt = timestamp("checked_at")
    val verifiedAt = timestamp("verified_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(subjectKind, subjectId, environment)
}

/** Who is barred from the ramp: a user, or a KYC identity key. Exactly one of the two is set on a row. */
object BlocksTable : Table("ramp.blocks") {
    val blockId = javaUUID("block_id")
    val userId = javaUUID("user_id").nullable()
    val identityKey = text("identity_key").nullable()
    val reason = text("reason")
    val note = text("note").default("")
    val sourceUserId = javaUUID("source_user_id").nullable()
    val createdBy = text("created_by")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(blockId)
}

/** Every move an order makes, written in the transaction that makes it: the only record of when each step happened. */
object OrderEventsTable : Table("ramp.order_events") {
    val eventId = javaUUID("event_id")
    val orderId = javaUUID("order_id")
    val merchantId = javaUUID("merchant_id")
    val network = text("network")
    val direction = text("direction")
    val state = text("state")
    val detail = text("detail").nullable()
    val at = timestamp("at")
    override val primaryKey = PrimaryKey(eventId)
}

/** The engine's settings in force, one versioned JSON document per network and direction. */
object EngineSettingsTable : Table("ramp.engine_settings") {
    val network = text("network")
    val direction = text("direction")
    val version = long("version")
    val document = text("document")
    val updatedBy = text("updated_by").nullable()
    val reason = text("reason").nullable()
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(network, direction)
}

/** Every version of the engine's settings there has been: what a routing decision's version points at. */
object EngineSettingsHistoryTable : Table("ramp.engine_settings_history") {
    val network = text("network")
    val direction = text("direction")
    val version = long("version")
    val document = text("document")
    val updatedBy = text("updated_by").nullable()
    val reason = text("reason").nullable()
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(network, direction, version)
}

/** One row per routing: who was looked at, how each scored or why it was left out, the order tried, what happened. */
object RoutingDecisionsTable : Table("ramp.routing_decisions") {
    val decisionId = javaUUID("decision_id")
    val quoteId = javaUUID("quote_id")
    val orderId = javaUUID("order_id").nullable()
    val merchantId = javaUUID("merchant_id").nullable()
    val network = text("network")
    val direction = text("direction")
    val fiatAmountMinor = long("fiat_amount_minor")
    val needMinor = long("need_minor")
    val settingsVersion = long("settings_version")
    val mode = text("mode")
    val outcome = text("outcome")
    val candidates = text("candidates")
    val ranked = text("ranked")
    val steps = text("steps")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(decisionId)
}

/** One network's pricing settings: where its buy and sell rates come from (V12). */
object PricingSettingsTable : Table("ramp.pricing_settings") {
    val network = text("network")
    val mode = text("mode")
    val buySpreadBps = integer("buy_spread_bps")
    val sellSpreadBps = integer("sell_spread_bps")
    val manualBuyRate = text("manual_buy_rate").nullable()
    val manualSellRate = text("manual_sell_rate").nullable()
    val manualExpiresAt = timestamp("manual_expires_at").nullable()
    val maxDeviationBps = integer("max_deviation_bps")
    val maxReferenceAgeHours = integer("max_reference_age_hours")
    val version = long("version")
    val updatedBy = text("updated_by").nullable()
    val reason = text("reason").nullable()
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(network)
}
