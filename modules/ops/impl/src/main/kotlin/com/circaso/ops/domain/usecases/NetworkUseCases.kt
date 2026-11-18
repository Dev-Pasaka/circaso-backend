package com.circaso.ops.domain.usecases

import com.circaso.identity.api.AccountAdmin
import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditAction
import com.circaso.ops.domain.models.UserAccount
import com.circaso.ops.domain.support.AuditTrail
import com.circaso.ops.domain.support.Reasons
import com.circaso.ops.domain.support.UserViews
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.flatMap
import com.circaso.wallet.api.NetworkAdmin
import com.circaso.wallet.api.OpsNetworkAccessChange
import com.circaso.wallet.api.OpsNetworkSettings
import com.circaso.wallet.api.OpsNetworkSettingsChange

/**
 * Grants or revokes a person's access to a wallet network (decision 2026-11-18): a testnet grant gives them the app's
 * Mainnet | Testnet switch (their testnet wallet is created when they first switch to it); a mainnet grant lets them
 * in while mainnet is in PREVIEW. Revoking never deletes a wallet or its funds. A reason is required and audited.
 */
class SetUserNetworkAccess(
    private val accounts: AccountAdmin,
    private val networks: NetworkAdmin,
    private val views: UserViews,
    private val trail: AuditTrail,
) {
    suspend operator fun invoke(
        actor: Actor,
        userId: UserId,
        network: Network,
        enabled: Boolean,
        reason: String,
    ): Resource<UserAccount> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        accounts.account(userId) ?: return Resource.error(OpsError.UserNotFound)
        val word = if (enabled) "grant" else "revoke"
        return trail
            .record<OpsNetworkAccessChange>(
                actor,
                AuditAction.USER_NETWORK_ACCESS,
                userId.toString(),
                reason = why,
                detail = "$word $network",
                outcome = { "$word $network${if (it.changed) "" else " (no change)"}" },
            ) { Resource.success(networks.setAccess(userId, network, enabled, actor.staffId, why)) }
            .flatMap { views.account(userId)?.let { Resource.success(it) } ?: Resource.error(OpsError.UserNotFound) }
    }
}

/** Every wallet network's settings, as the database has them now. */
class GetWalletNetworks(private val networks: NetworkAdmin) {
    suspend operator fun invoke(): Resource<List<OpsNetworkSettings>> = Resource.success(networks.settings())
}

/**
 * Replaces one network's settings (state, app id, default chain, chains and assets). A reason is required; the audit
 * line says what changed: the state, the version, and which chains were added, removed, retired or switched.
 */
class UpdateWalletNetwork(private val networks: NetworkAdmin, private val trail: AuditTrail) {
    suspend operator fun invoke(
        actor: Actor,
        settings: OpsNetworkSettings,
        reason: String,
    ): Resource<OpsNetworkSettingsChange> {
        val why = Reasons.check(reason) ?: return Resource.error(OpsError.ReasonRequired)
        return trail.record<OpsNetworkSettingsChange>(
            actor,
            AuditAction.WALLET_NETWORK_UPDATE,
            settings.network.name,
            reason = why,
            detail = "state ${settings.state}, ${settings.chains.size} chains",
            outcome = { describe(it) },
        ) { networks.updateSettings(settings, actor.staffId) }
    }

    private fun describe(change: OpsNetworkSettingsChange): String {
        val before = change.before
        val after = change.after
        val parts = mutableListOf("v${before.version}→v${after.version}")
        if (before.state != after.state) parts += "state ${before.state}→${after.state}"
        if (before.appId != after.appId) parts += "app id changed"
        if (before.defaultChain != after.defaultChain) parts += "default ${before.defaultChain}→${after.defaultChain}"
        val was = before.chains.associateBy { it.blockchain }
        val now = after.chains.associateBy { it.blockchain }
        (now.keys - was.keys).takeIf { it.isNotEmpty() }?.let { parts += "added ${it.joinToString(",")}" }
        (was.keys - now.keys).takeIf { it.isNotEmpty() }?.let { parts += "removed ${it.joinToString(",")}" }
        now.values.filter { was[it.blockchain]?.let { old -> old != it } == true }.takeIf { it.isNotEmpty() }?.let {
            parts += "changed ${it.joinToString(",") { chain -> chain.blockchain }}"
        }
        return parts.joinToString("; ")
    }
}
