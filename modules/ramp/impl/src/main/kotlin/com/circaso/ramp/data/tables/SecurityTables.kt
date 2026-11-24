package com.circaso.ramp.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** A merchant's passkey (V16): the authenticator's credential id and COSE public key, and its label. */
object MerchantPasskeysTable : Table("ramp.merchant_passkeys") {
    val passkeyId = javaUUID("passkey_id")
    val merchantId = javaUUID("merchant_id")
    val credentialId = binary("credential_id")
    val publicKeyCose = binary("public_key_cose")
    val signCount = long("sign_count").default(0)
    val label = text("label")
    val transports = text("transports").default("")
    val backupEligible = bool("backup_eligible").default(false)
    val backedUp = bool("backed_up").default(false)
    val createdAt = timestamp("created_at")
    val lastUsedAt = timestamp("last_used_at").nullable()
    override val primaryKey = PrimaryKey(passkeyId)
}

/** A merchant's security state (the withdrawals lock) and email preferences (V16). */
object MerchantSecurityTable : Table("ramp.merchant_security") {
    val merchantId = javaUUID("merchant_id")
    val orderEmails = bool("order_emails").default(true)
    val dailySummary = bool("daily_summary").default(false)
    val lockedAt = timestamp("locked_at").nullable()
    val lockedAlertId = javaUUID("locked_alert_id").nullable()
    val lockedCancelled = integer("locked_cancelled").default(0)
    val unlockedAt = timestamp("unlocked_at").nullable()
    val unlockedBy = text("unlocked_by").nullable()
    val unlockReason = text("unlock_reason").nullable()
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(merchantId)
}

/** An email to a merchant, written with the event it reports and sent by `ramp.merchant-alerts` (V16). */
object MerchantAlertsTable : Table("ramp.merchant_alerts") {
    val alertId = javaUUID("alert_id")
    val merchantId = javaUUID("merchant_id")
    val kind = text("kind")
    val security = bool("security")
    val dedupeKey = text("dedupe_key")
    val facts = text("facts")
    val state = text("state")
    val attempts = integer("attempts").default(0)
    val nextAttemptAt = timestamp("next_attempt_at")
    val failure = text("failure").nullable()
    val createdAt = timestamp("created_at")
    val sentAt = timestamp("sent_at").nullable()
    val linkExpiresAt = timestamp("link_expires_at").nullable()
    val linkUsedAt = timestamp("link_used_at").nullable()
    override val primaryKey = PrimaryKey(alertId)
}

/** Staff's merchant security settings: one row (V16). */
object SecuritySettingsTable : Table("ramp.security_settings") {
    val id = short("id")
    val merchantPasskeyRequired = bool("merchant_passkey_required").default(false)
    val version = long("version").default(1)
    val updatedBy = text("updated_by").nullable()
    val reason = text("reason").nullable()
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}
