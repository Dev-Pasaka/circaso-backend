package com.circaso.ramp.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.data.tables.MerchantAlertsTable
import com.circaso.ramp.data.tables.MerchantPasskeysTable
import com.circaso.ramp.data.tables.MerchantSecurityTable
import com.circaso.ramp.data.tables.SecuritySettingsTable
import com.circaso.ramp.domain.models.AlertFacts
import com.circaso.ramp.domain.models.AlertKind
import com.circaso.ramp.domain.models.AlertState
import com.circaso.ramp.domain.models.MerchantAlert
import com.circaso.ramp.domain.models.MerchantSecurity
import com.circaso.ramp.domain.models.Passkey
import com.circaso.ramp.domain.models.SecuritySettings
import com.circaso.ramp.domain.repositories.MerchantAlertRepository
import com.circaso.ramp.domain.repositories.MerchantSecurityRepository
import com.circaso.ramp.domain.repositories.PasskeyRepository
import com.circaso.ramp.domain.repositories.SecuritySettingsRepository
import com.circaso.shared.CircasoJson
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import java.time.Instant
import java.util.UUID

/** `ramp.merchant_passkeys`. */
class PasskeyRepositoryImpl(private val database: Database) : PasskeyRepository {
    override suspend fun insert(passkey: Passkey): Boolean =
        database.tx("passkeys.insert") {
            MerchantPasskeysTable
                .insertIgnore {
                    it[passkeyId] = passkey.passkeyId
                    it[merchantId] = passkey.merchantId.value
                    it[credentialId] = passkey.credentialId
                    it[publicKeyCose] = passkey.publicKeyCose
                    it[signCount] = passkey.signCount
                    it[label] = passkey.label
                    it[transports] = passkey.transports.joinToString(",")
                    it[backupEligible] = passkey.backupEligible
                    it[backedUp] = passkey.backedUp
                    it[createdAt] = passkey.createdAt
                    it[lastUsedAt] = passkey.lastUsedAt
                }.insertedCount > 0
        }

    override suspend fun find(passkeyId: UUID): Passkey? =
        database.tx("passkeys.find") {
            MerchantPasskeysTable
                .selectAll()
                .where { MerchantPasskeysTable.passkeyId eq passkeyId }
                .map { it.toPasskey() }
                .singleOrNull()
        }

    override suspend fun byCredentialId(credentialId: ByteArray): Passkey? =
        database.tx("passkeys.by-credential") {
            MerchantPasskeysTable
                .selectAll()
                .where { MerchantPasskeysTable.credentialId eq credentialId }
                .map { it.toPasskey() }
                .singleOrNull()
        }

    override suspend fun ofMerchant(merchantId: MerchantId): List<Passkey> =
        database.tx("passkeys.of-merchant") {
            MerchantPasskeysTable
                .selectAll()
                .where { MerchantPasskeysTable.merchantId eq merchantId.value }
                .orderBy(MerchantPasskeysTable.createdAt to SortOrder.ASC)
                .limit(MAX)
                .map { it.toPasskey() }
                .toList()
        }

    override suspend fun count(merchantId: MerchantId): Int = ofMerchant(merchantId).size

    override suspend fun used(
        passkeyId: UUID,
        signCount: Long,
        backedUp: Boolean,
        at: Instant,
    ) {
        database.tx("passkeys.used") {
            MerchantPasskeysTable.update({ MerchantPasskeysTable.passkeyId eq passkeyId }) {
                it[MerchantPasskeysTable.signCount] = signCount
                it[MerchantPasskeysTable.backedUp] = backedUp
                it[lastUsedAt] = at
            }
        }
    }

    override suspend fun delete(passkeyId: UUID): Boolean =
        database.tx("passkeys.delete") {
            MerchantPasskeysTable.deleteWhere { MerchantPasskeysTable.passkeyId eq passkeyId } > 0
        }

    private fun ResultRow.toPasskey() =
        Passkey(
            passkeyId = this[MerchantPasskeysTable.passkeyId],
            merchantId = MerchantId(this[MerchantPasskeysTable.merchantId]),
            credentialId = this[MerchantPasskeysTable.credentialId],
            publicKeyCose = this[MerchantPasskeysTable.publicKeyCose],
            signCount = this[MerchantPasskeysTable.signCount],
            label = this[MerchantPasskeysTable.label],
            transports = this[MerchantPasskeysTable.transports].split(',').filter { it.isNotBlank() },
            backupEligible = this[MerchantPasskeysTable.backupEligible],
            backedUp = this[MerchantPasskeysTable.backedUp],
            createdAt = this[MerchantPasskeysTable.createdAt],
            lastUsedAt = this[MerchantPasskeysTable.lastUsedAt],
        )

    private companion object {
        /** A bound for the read; the portal allows far fewer. */
        const val MAX = 50
    }
}

/** `ramp.merchant_security`: rows are made on first write. */
class MerchantSecurityRepositoryImpl(private val database: Database) : MerchantSecurityRepository {
    override suspend fun find(merchantId: MerchantId): MerchantSecurity =
        database.tx("merchant-security.find") {
            MerchantSecurityTable
                .selectAll()
                .where { MerchantSecurityTable.merchantId eq merchantId.value }
                .map { it.toSecurity() }
                .singleOrNull()
        } ?: MerchantSecurity(merchantId)

    override suspend fun setChoices(
        merchantId: MerchantId,
        orderEmails: Boolean,
        dailySummary: Boolean,
        at: Instant,
    ) {
        database.tx("merchant-security.choices") {
            ensure(merchantId, at)
            MerchantSecurityTable.update({ MerchantSecurityTable.merchantId eq merchantId.value }) {
                it[MerchantSecurityTable.orderEmails] = orderEmails
                it[MerchantSecurityTable.dailySummary] = dailySummary
                it[updatedAt] = at
            }
        }
    }

    override suspend fun lock(
        merchantId: MerchantId,
        alertId: UUID?,
        cancelled: Int,
        at: Instant,
    ): Boolean =
        database.tx("merchant-security.lock") {
            ensure(merchantId, at)
            MerchantSecurityTable.update({
                (MerchantSecurityTable.merchantId eq merchantId.value) and MerchantSecurityTable.lockedAt.isNull()
            }) {
                it[lockedAt] = at
                it[lockedAlertId] = alertId
                it[lockedCancelled] = cancelled
                it[updatedAt] = at
            } > 0
        }

    override suspend fun unlock(
        merchantId: MerchantId,
        by: String,
        reason: String,
        at: Instant,
    ): Boolean =
        database.tx("merchant-security.unlock") {
            MerchantSecurityTable.update({
                (MerchantSecurityTable.merchantId eq merchantId.value) and MerchantSecurityTable.lockedAt.isNotNull()
            }) {
                it[lockedAt] = null
                it[lockedAlertId] = null
                it[unlockedAt] = at
                it[unlockedBy] = by
                it[unlockReason] = reason
                it[updatedAt] = at
            } > 0
        }

    override suspend fun withDailySummary(): List<MerchantId> =
        database.tx("merchant-security.daily") {
            MerchantSecurityTable
                .select(MerchantSecurityTable.merchantId)
                .where { MerchantSecurityTable.dailySummary eq true }
                .limit(MAX_DAILY)
                .map { MerchantId(it[MerchantSecurityTable.merchantId]) }
                .toList()
        }

    private suspend fun ensure(merchantId: MerchantId, at: Instant) {
        MerchantSecurityTable.insertIgnore {
            it[MerchantSecurityTable.merchantId] = merchantId.value
            it[updatedAt] = at
        }
    }

    private fun ResultRow.toSecurity() =
        MerchantSecurity(
            merchantId = MerchantId(this[MerchantSecurityTable.merchantId]),
            orderEmails = this[MerchantSecurityTable.orderEmails],
            dailySummary = this[MerchantSecurityTable.dailySummary],
            lockedAt = this[MerchantSecurityTable.lockedAt],
            lockedAlertId = this[MerchantSecurityTable.lockedAlertId],
            lockedCancelled = this[MerchantSecurityTable.lockedCancelled],
            unlockedAt = this[MerchantSecurityTable.unlockedAt],
            unlockedBy = this[MerchantSecurityTable.unlockedBy],
            unlockReason = this[MerchantSecurityTable.unlockReason],
        )

    private companion object {
        const val MAX_DAILY = 5_000
    }
}

/** An alert's facts as stored: the domain's [AlertFacts], field for field. */
@Serializable
private data class StoredFacts(
    val time: String,
    val label: String? = null,
    val address: String? = null,
    val network: String? = null,
    val usableFrom: String? = null,
    val amount: String? = null,
    val txUrl: String? = null,
    val reason: String? = null,
    val what: String? = null,
    val method: String? = null,
    val device: String? = null,
    val location: String? = null,
    val cancelled: String? = null,
    val order: String? = null,
    val received: String? = null,
    val paid: String? = null,
    val earned: String? = null,
    val wallet: String? = null,
    val result: String? = null,
    val day: String? = null,
    val orders: String? = null,
    val bought: String? = null,
    val sold: String? = null,
)

private fun AlertFacts.toStored() =
    StoredFacts(
        time,
        label,
        address,
        network,
        usableFrom,
        amount,
        txUrl,
        reason,
        what,
        method,
        device,
        location,
        cancelled,
        order,
        received,
        paid,
        earned,
        wallet,
        result,
        day,
        orders,
        bought,
        sold,
    )

private fun StoredFacts.toFacts() =
    AlertFacts(
        time,
        label,
        address,
        network,
        usableFrom,
        amount,
        txUrl,
        reason,
        what,
        method,
        device,
        location,
        cancelled,
        order,
        received,
        paid,
        earned,
        wallet,
        result,
        day,
        orders,
        bought,
        sold,
    )

/** `ramp.merchant_alerts`. */
class MerchantAlertRepositoryImpl(private val database: Database) : MerchantAlertRepository {
    override suspend fun insert(alert: MerchantAlert): Boolean =
        database.tx("merchant-alerts.insert") {
            MerchantAlertsTable
                .insertIgnore {
                    it[alertId] = alert.alertId
                    it[merchantId] = alert.merchantId.value
                    it[kind] = alert.kind.name
                    it[security] = alert.kind.security
                    it[dedupeKey] = alert.dedupeKey
                    it[facts] = CircasoJson.encodeToString(StoredFacts.serializer(), alert.facts.toStored())
                    it[state] = alert.state.name
                    it[attempts] = alert.attempts
                    it[nextAttemptAt] = alert.nextAttemptAt
                    it[createdAt] = alert.createdAt
                    it[linkExpiresAt] = alert.linkExpiresAt
                }.insertedCount > 0
        }

    override suspend fun find(alertId: UUID): MerchantAlert? =
        database.tx("merchant-alerts.find") {
            MerchantAlertsTable
                .selectAll()
                .where { MerchantAlertsTable.alertId eq alertId }
                .map { it.toAlert() }
                .singleOrNull()
        }

    override suspend fun due(now: Instant, limit: Int): List<MerchantAlert> =
        database.tx("merchant-alerts.due") {
            MerchantAlertsTable
                .selectAll()
                .where {
                    (MerchantAlertsTable.state eq AlertState.PENDING.name) and
                        (MerchantAlertsTable.nextAttemptAt lessEq now)
                }.orderBy(MerchantAlertsTable.nextAttemptAt to SortOrder.ASC)
                .limit(limit)
                .map { it.toAlert() }
                .toList()
        }

    override suspend fun sent(alertId: UUID, at: Instant) {
        database.tx("merchant-alerts.sent") {
            MerchantAlertsTable.update({ pending(alertId) }) {
                it[state] = AlertState.SENT.name
                it[sentAt] = at
                it[failure] = null
            }
        }
    }

    override suspend fun skipped(
        alertId: UUID,
        why: String,
        at: Instant,
    ) {
        database.tx("merchant-alerts.skipped") {
            MerchantAlertsTable.update({ pending(alertId) }) {
                it[state] = AlertState.SKIPPED.name
                it[failure] = why
            }
        }
    }

    override suspend fun retry(
        alertId: UUID,
        attempts: Int,
        next: Instant,
        why: String,
    ) {
        database.tx("merchant-alerts.retry") {
            MerchantAlertsTable.update({ pending(alertId) }) {
                it[MerchantAlertsTable.attempts] = attempts
                it[nextAttemptAt] = next
                it[failure] = why
            }
        }
    }

    override suspend fun failed(
        alertId: UUID,
        attempts: Int,
        why: String,
    ) {
        database.tx("merchant-alerts.failed") {
            MerchantAlertsTable.update({ pending(alertId) }) {
                it[state] = AlertState.FAILED.name
                it[MerchantAlertsTable.attempts] = attempts
                it[failure] = why
            }
        }
    }

    override suspend fun useLink(alertId: UUID, at: Instant): Boolean =
        database.tx("merchant-alerts.use-link") {
            MerchantAlertsTable.update({
                (MerchantAlertsTable.alertId eq alertId) and MerchantAlertsTable.linkUsedAt.isNull()
            }) { it[linkUsedAt] = at } > 0
        }

    override suspend fun recent(merchantId: MerchantId, limit: Int): List<MerchantAlert> =
        database.tx("merchant-alerts.recent") {
            MerchantAlertsTable
                .selectAll()
                .where { MerchantAlertsTable.merchantId eq merchantId.value }
                .orderBy(MerchantAlertsTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { it.toAlert() }
                .toList()
        }

    private fun pending(alertId: UUID) =
        (MerchantAlertsTable.alertId eq alertId) and (MerchantAlertsTable.state eq AlertState.PENDING.name)

    private fun ResultRow.toAlert() =
        MerchantAlert(
            alertId = this[MerchantAlertsTable.alertId],
            merchantId = MerchantId(this[MerchantAlertsTable.merchantId]),
            kind = AlertKind.valueOf(this[MerchantAlertsTable.kind]),
            dedupeKey = this[MerchantAlertsTable.dedupeKey],
            facts = CircasoJson.decodeFromString(StoredFacts.serializer(), this[MerchantAlertsTable.facts]).toFacts(),
            state = AlertState.valueOf(this[MerchantAlertsTable.state]),
            attempts = this[MerchantAlertsTable.attempts],
            nextAttemptAt = this[MerchantAlertsTable.nextAttemptAt],
            createdAt = this[MerchantAlertsTable.createdAt],
            linkExpiresAt = this[MerchantAlertsTable.linkExpiresAt],
            linkUsedAt = this[MerchantAlertsTable.linkUsedAt],
            failure = this[MerchantAlertsTable.failure],
            sentAt = this[MerchantAlertsTable.sentAt],
        )
}

/** `ramp.security_settings`: the one row, seeded on first read. */
class SecuritySettingsRepositoryImpl(private val database: Database) : SecuritySettingsRepository {
    override suspend fun get(): SecuritySettings =
        database.tx("security-settings.get") {
            SecuritySettingsTable.insertIgnore {
                it[id] = ROW
                it[updatedAt] = Instant.EPOCH
            }
            SecuritySettingsTable
                .selectAll()
                .where { SecuritySettingsTable.id eq ROW }
                .map {
                    SecuritySettings(
                        merchantPasskeyRequired = it[SecuritySettingsTable.merchantPasskeyRequired],
                        version = it[SecuritySettingsTable.version],
                        updatedBy = it[SecuritySettingsTable.updatedBy],
                        reason = it[SecuritySettingsTable.reason],
                        updatedAt = it[SecuritySettingsTable.updatedAt].takeIf { at -> at != Instant.EPOCH },
                    )
                }.singleOrNull()
        } ?: SecuritySettings()

    override suspend fun update(
        settings: SecuritySettings,
        version: Long,
        at: Instant,
    ): Boolean =
        database.tx("security-settings.update") {
            SecuritySettingsTable.update({
                (SecuritySettingsTable.id eq ROW) and (SecuritySettingsTable.version eq version)
            }) {
                it[merchantPasskeyRequired] = settings.merchantPasskeyRequired
                it[updatedBy] = settings.updatedBy
                it[reason] = settings.reason
                it[updatedAt] = at
                it[SecuritySettingsTable.version] = version + 1
            } > 0
        }

    private companion object {
        const val ROW: Short = 1
    }
}
