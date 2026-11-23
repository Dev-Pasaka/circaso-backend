package com.circaso.ramp.data.repositories

import com.circaso.infrastructure.api.Database
import com.circaso.ramp.api.MerchantId
import com.circaso.ramp.data.tables.MerchantCredentialsTable
import com.circaso.ramp.data.tables.MerchantSessionsTable
import com.circaso.ramp.data.tables.MerchantWalletsTable
import com.circaso.ramp.data.tables.MerchantsTable
import com.circaso.ramp.data.tables.RevenueWalletsTable
import com.circaso.ramp.domain.models.Candidate
import com.circaso.ramp.domain.models.KesBalances
import com.circaso.ramp.domain.models.Merchant
import com.circaso.ramp.domain.models.MerchantSession
import com.circaso.ramp.domain.models.MerchantState
import com.circaso.ramp.domain.models.MerchantType
import com.circaso.ramp.domain.models.MerchantWallet
import com.circaso.ramp.domain.models.OfflineReason
import com.circaso.ramp.domain.models.PaymentsIssue
import com.circaso.ramp.domain.models.PayoutIssue
import com.circaso.ramp.domain.models.RevenueWallet
import com.circaso.ramp.domain.models.SealedCredentials
import com.circaso.ramp.domain.models.StoredWebhookSecret
import com.circaso.ramp.domain.repositories.CredentialRepository
import com.circaso.ramp.domain.repositories.MerchantRepository
import com.circaso.ramp.domain.repositories.MerchantWalletRepository
import com.circaso.ramp.domain.repositories.RevenueWalletRepository
import com.circaso.ramp.domain.repositories.SessionRepository
import com.circaso.shared.Network
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.JoinType
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.isNotNull
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.core.statements.UpdateStatement
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.time.Instant

class MerchantRepositoryImpl(private val database: Database) : MerchantRepository {
    override suspend fun find(merchantId: MerchantId): Merchant? =
        database.tx("merchants.find") { one { MerchantsTable.merchantId eq merchantId.value } }

    override suspend fun byEmail(email: String): Merchant? =
        database.tx("merchants.by-email") { one { MerchantsTable.contactEmail eq email } }

    override suspend fun byCallbackKey(callbackKey: String): Merchant? =
        database.tx("merchants.by-callback-key") { one { MerchantsTable.callbackKey eq callbackKey } }

    override suspend fun insert(merchant: Merchant): Boolean =
        database.tx("merchants.insert") {
            MerchantsTable
                .insertIgnore {
                    it[merchantId] = merchant.merchantId.value
                    it[contactEmail] = merchant.email
                    it[displayName] = merchant.displayName
                    it[contactPhone] = merchant.contactPhone
                    it[state] = merchant.state.name
                    it[callbackKey] = merchant.callbackKey
                    it[onlineUntil] = merchant.onlineUntil
                    it[lastAssignedAt] = merchant.lastAssignedAt
                    it[approvedBy] = merchant.approvedBy
                    it[approvedAt] = merchant.approvedAt
                    it[createdAt] = merchant.createdAt
                }.insertedCount > 0
        }

    override suspend fun page(before: MerchantId?, limit: Int): List<Merchant> =
        database.tx("merchants.page") {
            MerchantsTable
                .selectAll()
                .where { if (before == null) Op.TRUE else MerchantsTable.merchantId less before.value }
                .orderBy(MerchantsTable.merchantId to SortOrder.DESC)
                .limit(limit)
                .map { it.toMerchant() }
                .toList()
        }

    override suspend fun pool(
        network: Network,
        blockchain: String,
        limit: Int,
    ): List<Candidate> =
        database.tx("merchants.pool") {
            MerchantsTable
                .join(MerchantWalletsTable, JoinType.INNER, MerchantsTable.merchantId, MerchantWalletsTable.merchantId)
                .selectAll()
                .where {
                    (MerchantWalletsTable.network eq network.name) and (MerchantWalletsTable.blockchain eq blockchain)
                }.orderBy(MerchantsTable.merchantId to SortOrder.ASC)
                .limit(limit)
                .map { Candidate(it.toMerchant(), it.toWallet()) }
                .toList()
        }

    override suspend fun setTier(
        merchantId: MerchantId,
        type: MerchantType,
        orderLimitMinor: Long?,
    ): Boolean =
        database.tx("merchants.set-tier") {
            MerchantsTable.update({ MerchantsTable.merchantId eq merchantId.value }) {
                it[merchantType] = type.name
                it[MerchantsTable.orderLimitMinor] = orderLimitMinor
            } > 0
        }

    override suspend fun setOffers(
        merchantId: MerchantId,
        onRamp: Boolean,
        offRamp: Boolean,
    ) {
        database.tx("merchants.set-offers") {
            MerchantsTable.update({ MerchantsTable.merchantId eq merchantId.value }) {
                it[offersOnramp] = onRamp
                it[offersOfframp] = offRamp
            }
        }
    }

    override suspend fun lock(merchantId: MerchantId): Merchant? =
        database.tx("merchants.lock") {
            MerchantsTable
                .selectAll()
                .where { MerchantsTable.merchantId eq merchantId.value }
                .forUpdate(ForUpdateOption.ForUpdate)
                .map { it.toMerchant() }
                .singleOrNull()
        }

    override suspend fun markAssigned(merchantId: MerchantId, at: Instant) {
        database.tx("merchants.mark-assigned") {
            MerchantsTable.update({ MerchantsTable.merchantId eq merchantId.value }) { it[lastAssignedAt] = at }
        }
    }

    override suspend fun setProfile(
        merchantId: MerchantId,
        displayName: String,
        contactPhone: String,
    ) {
        database.tx("merchants.set-profile") {
            MerchantsTable.update({ MerchantsTable.merchantId eq merchantId.value }) {
                it[MerchantsTable.displayName] = displayName
                it[MerchantsTable.contactPhone] = contactPhone
            }
            MerchantsTable.update({
                (MerchantsTable.merchantId eq merchantId.value) and (MerchantsTable.state eq MerchantState.NEW.name)
            }) {
                it[state] = MerchantState.APPLIED.name
            }
        }
    }

    override suspend fun setState(
        merchantId: MerchantId,
        to: MerchantState,
        from: Set<MerchantState>,
        by: String,
        at: Instant,
    ): Boolean =
        database.tx("merchants.set-state") {
            MerchantsTable.update({
                (MerchantsTable.merchantId eq merchantId.value) and (MerchantsTable.state inList from.map { it.name })
            }) {
                it[state] = to.name
                if (to == MerchantState.APPROVED) {
                    it[approvedBy] = by
                    it[approvedAt] = at
                }
            } > 0
        }

    override suspend fun setCallbackKey(merchantId: MerchantId, callbackKey: String) {
        database.tx("merchants.set-callback-key") {
            MerchantsTable.update({ MerchantsTable.merchantId eq merchantId.value }) {
                it[MerchantsTable.callbackKey] = callbackKey
            }
        }
    }

    override suspend fun goOnline(
        merchantId: MerchantId,
        until: Instant?,
        since: Instant,
    ) {
        database.tx("merchants.go-online") {
            MerchantsTable.update({ MerchantsTable.merchantId eq merchantId.value }) {
                it[onlineUntil] = until
                it[onlineOpenEnded] = until == null
                it[onlineSince] = since
                it[offlineReason] = null
                it[offlineAt] = null
            }
        }
    }

    override suspend fun goOffline(
        merchantId: MerchantId,
        reason: OfflineReason,
        at: Instant,
    ) {
        database.tx("merchants.go-offline") {
            MerchantsTable.update({ MerchantsTable.merchantId eq merchantId.value }) { it.endSession(reason, at) }
        }
    }

    override suspend fun endLostSession(
        merchantId: MerchantId,
        since: Instant?,
        at: Instant,
    ): Boolean =
        database.tx("merchants.end-lost-session") {
            val sameSession = since?.let { MerchantsTable.onlineSince eq it } ?: MerchantsTable.onlineSince.isNull()
            MerchantsTable.update(
                { (MerchantsTable.merchantId eq merchantId.value) and hasSession() and sameSession },
            ) {
                it.endSession(OfflineReason.RESTART, at)
            } > 0
        }

    override suspend fun withSessions(limit: Int): List<Merchant> =
        database.tx("merchants.with-sessions") {
            MerchantsTable
                .selectAll()
                .where { hasSession() }
                .orderBy(MerchantsTable.merchantId to SortOrder.ASC)
                .limit(limit)
                .map { it.toMerchant() }
                .toList()
        }

    override suspend fun setPaymentsIssue(
        merchantId: MerchantId,
        issue: PaymentsIssue?,
        at: Instant,
    ) {
        database.tx("merchants.set-payments-issue") {
            MerchantsTable.update({ MerchantsTable.merchantId eq merchantId.value }) {
                it[paymentsIssue] = issue?.name
                it[paymentsIssueAt] = at.takeIf { issue != null }
            }
        }
    }

    override suspend fun endPassedWindows(now: Instant): Int =
        database.tx("merchants.end-passed-windows") {
            MerchantsTable.update({ MerchantsTable.onlineUntil less now }) {
                it.endSession(OfflineReason.WINDOW_ENDED, now)
            }
        }

    override suspend fun setPayoutAccount(merchantId: MerchantId, accountId: String?) {
        database.tx("merchants.set-payout-account") {
            MerchantsTable.update({ MerchantsTable.merchantId eq merchantId.value }) { it[payoutAccountId] = accountId }
        }
    }

    override suspend fun setPayoutIssue(
        merchantId: MerchantId,
        issue: PayoutIssue?,
        at: Instant,
    ) {
        database.tx("merchants.set-payout-issue") {
            MerchantsTable.update({ MerchantsTable.merchantId eq merchantId.value }) {
                it[payoutIssue] = issue?.name
                it[payoutIssueAt] = at.takeIf { issue != null }
            }
        }
    }

    override suspend fun recordBalances(merchantId: MerchantId, balances: KesBalances) {
        database.tx("merchants.record-balances") {
            MerchantsTable.update({ MerchantsTable.merchantId eq merchantId.value }) {
                it[payoutBalanceMinor] = balances.payoutMinor
                it[payoutBalanceSource] = balances.payoutSource
                it[feeBalanceMinor] = balances.feeMinor
                it[feeBalanceSource] = balances.feeSource
                it[balancesReadAt] = balances.readAt
            }
        }
    }

    override suspend fun balances(merchantId: MerchantId): KesBalances =
        database.tx("merchants.balances") {
            MerchantsTable
                .select(
                    MerchantsTable.payoutBalanceMinor,
                    MerchantsTable.payoutBalanceSource,
                    MerchantsTable.feeBalanceMinor,
                    MerchantsTable.feeBalanceSource,
                    MerchantsTable.balancesReadAt,
                ).where { MerchantsTable.merchantId eq merchantId.value }
                .map {
                    KesBalances(
                        payoutMinor = it[MerchantsTable.payoutBalanceMinor],
                        payoutSource = it[MerchantsTable.payoutBalanceSource],
                        feeMinor = it[MerchantsTable.feeBalanceMinor],
                        feeSource = it[MerchantsTable.feeBalanceSource],
                        readAt = it[MerchantsTable.balancesReadAt],
                    )
                }.singleOrNull() ?: KesBalances.UNREAD
        }

    override suspend fun setWebhookSecret(
        merchantId: MerchantId,
        sealed: ByteArray?,
        at: Instant,
    ) {
        database.tx("merchants.set-webhook-secret") {
            MerchantsTable.update({ MerchantsTable.merchantId eq merchantId.value }) {
                it[webhookSecretSealed] = sealed
                it[webhookSecretUpdatedAt] = at.takeIf { sealed != null }
            }
        }
    }

    override suspend fun webhookSecret(callbackKey: String): StoredWebhookSecret? =
        database.tx("merchants.webhook-secret") {
            MerchantsTable
                .select(MerchantsTable.merchantId, MerchantsTable.webhookSecretSealed)
                .where { MerchantsTable.callbackKey eq callbackKey }
                .map { row ->
                    row[MerchantsTable.webhookSecretSealed]?.let {
                        StoredWebhookSecret(MerchantId(row[MerchantsTable.merchantId]), it)
                    }
                }.singleOrNull()
        }

    private suspend fun one(where: () -> Op<Boolean>): Merchant? =
        MerchantsTable
            .selectAll()
            .where { where() }
            .map { it.toMerchant() }
            .singleOrNull()
}

/** Matches the partial index `merchants_sessions_idx`: a session is open, timed or not. */
private fun hasSession(): Op<Boolean> =
    (MerchantsTable.onlineOpenEnded eq true) or MerchantsTable.onlineUntil.isNotNull()

/** Ends whatever session is open, keeping why and when for the portal. */
private fun UpdateStatement.endSession(reason: OfflineReason, at: Instant) {
    this[MerchantsTable.onlineUntil] = null
    this[MerchantsTable.onlineOpenEnded] = false
    this[MerchantsTable.onlineSince] = null
    this[MerchantsTable.offlineReason] = reason.name
    this[MerchantsTable.offlineAt] = at
}

private fun ResultRow.toMerchant() =
    Merchant(
        merchantId = MerchantId(this[MerchantsTable.merchantId]),
        email = this[MerchantsTable.contactEmail],
        displayName = this[MerchantsTable.displayName],
        contactPhone = this[MerchantsTable.contactPhone],
        state = MerchantState.valueOf(this[MerchantsTable.state]),
        callbackKey = this[MerchantsTable.callbackKey],
        onlineUntil = this[MerchantsTable.onlineUntil],
        lastAssignedAt = this[MerchantsTable.lastAssignedAt],
        approvedBy = this[MerchantsTable.approvedBy],
        approvedAt = this[MerchantsTable.approvedAt],
        createdAt = this[MerchantsTable.createdAt],
        paymentsIssue = this[MerchantsTable.paymentsIssue]?.let(PaymentsIssue::valueOf),
        paymentsIssueAt = this[MerchantsTable.paymentsIssueAt],
        merchantType = MerchantType.valueOf(this[MerchantsTable.merchantType]),
        orderLimitMinor = this[MerchantsTable.orderLimitMinor],
        offersOnramp = this[MerchantsTable.offersOnramp],
        offersOfframp = this[MerchantsTable.offersOfframp],
        payoutAccountId = this[MerchantsTable.payoutAccountId],
        payoutIssue = this[MerchantsTable.payoutIssue]?.let(PayoutIssue::valueOf),
        payoutIssueAt = this[MerchantsTable.payoutIssueAt],
        webhookSecretUpdatedAt = this[MerchantsTable.webhookSecretUpdatedAt],
        onlineOpenEnded = this[MerchantsTable.onlineOpenEnded],
        onlineSince = this[MerchantsTable.onlineSince],
        offlineReason = this[MerchantsTable.offlineReason]?.let(OfflineReason::valueOf),
        offlineAt = this[MerchantsTable.offlineAt],
    )

private fun ResultRow.toWallet() =
    MerchantWallet(
        merchantId = MerchantId(this[MerchantWalletsTable.merchantId]),
        network = Network.valueOf(this[MerchantWalletsTable.network]),
        blockchain = this[MerchantWalletsTable.blockchain],
        custodyWalletId = this[MerchantWalletsTable.custodyWalletId],
        address = this[MerchantWalletsTable.walletAddress],
    )

class MerchantWalletRepositoryImpl(private val database: Database) : MerchantWalletRepository {
    override suspend fun find(merchantId: MerchantId, network: Network): MerchantWallet? =
        database.tx("merchant-wallets.find") {
            MerchantWalletsTable
                .selectAll()
                .where {
                    (MerchantWalletsTable.merchantId eq merchantId.value) and
                        (MerchantWalletsTable.network eq network.name)
                }.map { it.toWallet() }
                .singleOrNull()
        }

    override suspend fun ofMerchant(merchantId: MerchantId): List<MerchantWallet> =
        database.tx("merchant-wallets.of-merchant") {
            MerchantWalletsTable
                .selectAll()
                .where { MerchantWalletsTable.merchantId eq merchantId.value }
                .map { it.toWallet() }
                .toList()
        }

    override suspend fun insert(wallet: MerchantWallet, at: Instant): Boolean =
        database.tx("merchant-wallets.insert") {
            MerchantWalletsTable
                .insertIgnore {
                    it[merchantId] = wallet.merchantId.value
                    it[network] = wallet.network.name
                    it[blockchain] = wallet.blockchain
                    it[custodyWalletId] = wallet.custodyWalletId
                    it[walletAddress] = wallet.address
                    it[createdAt] = at
                }.insertedCount > 0
        }

    override suspend fun approvedWithout(network: Network, limit: Int): List<MerchantId> =
        database.tx("merchant-wallets.approved-without") {
            MerchantsTable
                .join(
                    MerchantWalletsTable,
                    JoinType.LEFT,
                    MerchantsTable.merchantId,
                    MerchantWalletsTable.merchantId,
                    additionalConstraint = { MerchantWalletsTable.network eq network.name },
                ).select(MerchantsTable.merchantId)
                .where {
                    (MerchantsTable.state eq MerchantState.APPROVED.name) and
                        MerchantWalletsTable.merchantId.isNull()
                }.limit(limit)
                .map { MerchantId(it[MerchantsTable.merchantId]) }
                .toList()
        }
}

class RevenueWalletRepositoryImpl(private val database: Database) : RevenueWalletRepository {
    override suspend fun find(network: Network): RevenueWallet? =
        database.tx("revenue-wallets.find") {
            RevenueWalletsTable
                .selectAll()
                .where { RevenueWalletsTable.network eq network.name }
                .map {
                    RevenueWallet(
                        network,
                        it[RevenueWalletsTable.blockchain],
                        it[RevenueWalletsTable.custodyWalletId],
                        it[RevenueWalletsTable.walletAddress],
                    )
                }.singleOrNull()
        }

    override suspend fun insert(wallet: RevenueWallet, at: Instant): Boolean =
        database.tx("revenue-wallets.insert") {
            RevenueWalletsTable
                .insertIgnore {
                    it[network] = wallet.network.name
                    it[blockchain] = wallet.blockchain
                    it[custodyWalletId] = wallet.custodyWalletId
                    it[walletAddress] = wallet.address
                    it[createdAt] = at
                }.insertedCount > 0
        }
}

/** `ramp.merchant_credentials`: one sealed row per merchant, replaced whole. Nothing in it is plaintext but the till. */
class CredentialRepositoryImpl(private val database: Database) : CredentialRepository {
    override suspend fun find(merchantId: MerchantId): SealedCredentials? =
        database.tx("credentials.find") {
            MerchantCredentialsTable
                .selectAll()
                .where { MerchantCredentialsTable.merchantId eq merchantId.value }
                .map {
                    SealedCredentials(
                        provider = it[MerchantCredentialsTable.provider],
                        channelId = it[MerchantCredentialsTable.channelId],
                        sealed = it[MerchantCredentialsTable.sealed],
                        wrappedKey = it[MerchantCredentialsTable.wrappedKey],
                        pinSalt = it[MerchantCredentialsTable.pinSalt],
                        kdfParams = it[MerchantCredentialsTable.kdfParams],
                    )
                }.singleOrNull()
        }

    override suspend fun save(
        merchantId: MerchantId,
        sealed: SealedCredentials,
        at: Instant,
    ) {
        database.tx("credentials.save") {
            MerchantCredentialsTable.upsert(keys = arrayOf(MerchantCredentialsTable.merchantId)) {
                it[MerchantCredentialsTable.merchantId] = merchantId.value
                it[provider] = sealed.provider
                it[channelId] = sealed.channelId
                it[MerchantCredentialsTable.sealed] = sealed.sealed
                it[wrappedKey] = sealed.wrappedKey
                it[pinSalt] = sealed.pinSalt
                it[kdfParams] = sealed.kdfParams
                it[updatedAt] = at
            }
        }
    }
}

/** `ramp.merchant_sessions`: found by the token's hash; an expired row is gone to every read. */
class SessionRepositoryImpl(private val database: Database) : SessionRepository {
    override suspend fun insert(
        tokenHash: ByteArray,
        merchantId: MerchantId,
        now: Instant,
        expiresAt: Instant,
    ) {
        database.tx("sessions.insert") {
            MerchantSessionsTable.insertIgnore {
                it[MerchantSessionsTable.tokenHash] = tokenHash
                it[MerchantSessionsTable.merchantId] = merchantId.value
                it[createdAt] = now
                it[MerchantSessionsTable.expiresAt] = expiresAt
            }
        }
    }

    override suspend fun find(tokenHash: ByteArray, now: Instant): MerchantSession? =
        database.tx("sessions.find") {
            MerchantSessionsTable
                .selectAll()
                .where {
                    (MerchantSessionsTable.tokenHash eq tokenHash) and
                        (MerchantSessionsTable.expiresAt greater now)
                }.map {
                    MerchantSession(
                        MerchantId(it[MerchantSessionsTable.merchantId]),
                        it[MerchantSessionsTable.expiresAt],
                    )
                }.singleOrNull()
        }

    override suspend fun extend(tokenHash: ByteArray, expiresAt: Instant) {
        database.tx("sessions.extend") {
            MerchantSessionsTable.update({ MerchantSessionsTable.tokenHash eq tokenHash }) {
                it[MerchantSessionsTable.expiresAt] = expiresAt
            }
        }
    }

    override suspend fun delete(tokenHash: ByteArray): Boolean =
        database.tx("sessions.delete") {
            MerchantSessionsTable.deleteWhere { MerchantSessionsTable.tokenHash eq tokenHash } > 0
        }
}
