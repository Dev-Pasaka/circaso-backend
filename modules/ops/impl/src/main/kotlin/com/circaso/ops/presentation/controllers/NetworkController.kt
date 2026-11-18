package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.presentation.dto.UpdateWalletNetworkRequest
import com.circaso.ops.presentation.dto.WalletNetworkDto
import com.circaso.ops.presentation.dto.WalletNetworksResponse
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.TAG_NETWORKS
import com.circaso.ops.presentation.mappers.actor
import com.circaso.ops.presentation.mappers.parseEnum
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toDto
import com.circaso.ops.presentation.mappers.toSettings
import com.circaso.ops.presentation.services.NetworkService
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.shared.flatMap
import com.circaso.shared.map
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * The wallet networks' settings (decision 2026-11-18): the environment holds keys and secrets only; the state, app
 * id, chains and assets live in the database and are edited here. ADMIN only.
 */
fun Route.networkController(networks: NetworkService) {
    route("/ops/wallet/networks") {
        staffOnly(Permission.WALLET_SETTINGS.roles) {
            get {
                val result = networks.networks()
                call.respondDefault(result.toDefaultResponse { all -> WalletNetworksResponse(all.map { it.toDto() }) })
            }.docs<NoBody, WalletNetworksResponse>(
                "OPS · The wallet networks and their settings",
                TAG_NETWORKS,
                listOf(FORBIDDEN),
                notes =
                    "${roles("ADMIN")} Mainnet first. `state`: OFF (nobody), PREVIEW (only people granted access " +
                        "to it), ON. Each chain with whether it is offered (`enabled`), whether it still receives " +
                        "(`receiveEnabled`), its account type and its assets.",
            )

            put("{network}") {
                val request = call.receive<UpdateWalletNetworkRequest>()
                val result =
                    parseEnum<Network>(call.parameters["network"], "network").flatMap { network ->
                        if (network == null) {
                            Resource.error(SharedError.NotFound)
                        } else {
                            networks.update(call.actor(), request.toSettings(network), request.reason)
                        }
                    }
                call.respondDefault(result.map { it.after }.toDefaultResponse { it.toDto() })
            }.docs<UpdateWalletNetworkRequest, WalletNetworkDto>(
                "OPS · Change a wallet network's settings",
                TAG_NETWORKS,
                listOf(
                    "ops.reason_required",
                    "wallet.network_settings_invalid",
                    "wallet.network_settings_changed",
                    MALFORMED,
                    FORBIDDEN,
                ),
                notes =
                    "${roles("ADMIN")} Replaces the network's settings; audited with the reason and what changed. " +
                        "The default chain must be enabled and receiving. A chain some wallet is on may be retired " +
                        "(`receiveEnabled: false`) but not removed or disabled. A chain's name must belong to the " +
                        "network (a testnet's ends in SEPOLIA, AMOY, DEVNET, FUJI or TESTNET). A network that isn't " +
                        "OFF needs its `appId`. Both servers use the new settings within about ten seconds.",
            )
        }
    }
}
