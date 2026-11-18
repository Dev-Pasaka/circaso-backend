package com.circaso.integrations.impl.circle

import com.circaso.integrations.api.CustodyNotifications
import com.circaso.integrations.api.CustodySetup
import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.integrations.api.WebhookVendor
import com.circaso.shared.Network
import com.circaso.shared.Resource
import java.time.Clock
import java.time.Instant

/** One Circle account: the client that carries its API key, and its settings as the database has them now. */
internal class CircleAccount(val network: Network, val client: CircleClient, val settings: () -> CircleSettings)

/**
 * Circle's accounts by network (decision 2026-11-18): the testnet's always, the mainnet's when its key is set. Each
 * gets its own custody (and with it its own user-token cache) and its own webhook, verified against its own keys.
 */
internal class CircleAccounts(val accounts: Map<Network, CircleAccount>, private val clock: Clock = Clock.systemUTC()) {
    val custodies: Map<Network, StablecoinCustody> =
        accounts.mapValues { (_, account) -> CircleCustody(account.client, account.settings, clock) }

    /**
     * The webhook for [network]'s account at its own path. A network with no account still answers at its path, and
     * refuses every delivery: nothing can be verified without that account's keys.
     */
    fun webhook(network: Network, notifications: () -> CustodyNotifications?): WebhookVendor {
        val name = if (network == Network.TESTNET) CircleWebhook.TESTNET_NAME else CircleWebhook.MAINNET_NAME
        val account = accounts[network] ?: return UnconfiguredWebhook(name)
        return CircleWebhook(
            client = account.client,
            settings = account.settings,
            clock = clock,
            name = name,
            network = network,
            notifications = notifications,
        )
    }
}

/** The path of an account that isn't configured: every delivery fails its signature check. */
private class UnconfiguredWebhook(override val name: String) : WebhookVendor {
    override suspend fun verifySignature(headers: Map<String, String>, body: ByteArray): Boolean = false

    override fun sentAt(headers: Map<String, String>, body: ByteArray): Instant? = null

    override fun deliveryId(headers: Map<String, String>, body: ByteArray): String? = null

    override suspend fun translate(body: ByteArray): Resource<Unit> =
        Resource.error(IntegrationError.Unavailable("Custody"))
}

/** Custody's settings as Circle's classes read them. */
internal fun CustodySetup.toCircleSettings(): CircleSettings =
    CircleSettings(appId = appId, supported = supported, accountTypes = accountTypes)
