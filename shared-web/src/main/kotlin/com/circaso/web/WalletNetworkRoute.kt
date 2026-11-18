package com.circaso.web

import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.Scope
import com.circaso.shared.WalletNetwork
import com.circaso.shared.WalletNetworkGate
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import io.ktor.server.routing.intercept
import io.ktor.util.AttributeKey
import kotlinx.coroutines.withContext

/** The header a phone names its wallet network in: `MAINNET` or `TESTNET` (decision 2026-11-18). */
const val WALLET_NETWORK_HEADER = "X-Wallet-Network"

/**
 * The wallet's gate, put on the application by the gateway at startup. A server without one (a module's own route
 * test) reads the header alone: the network it names, or testnet.
 */
val WalletNetworkGateKey = AttributeKey<WalletNetworkGate>("circaso.wallet-network-gate")

private val CallNetworkKey = AttributeKey<Network>("circaso.wallet-network")

/** The network this call was admitted for. Only valid inside `walletNetwork { }`. */
fun ApplicationCall.walletNetwork(): Network = attributes.getOrNull(CallNetworkKey) ?: Network.TESTNET

/**
 * A route group whose work is for one wallet network (every wallet, group money, PIN Vault and agent wallet route).
 * Goes inside `authenticate { }`: it asks the wallet's gate which network this person's request is for
 * (`X-Wallet-Network`, or the fallback for an app that sends none), answers `wallet.network_unavailable` when they may
 * not use it, and runs the handler with that network in its coroutine context, where the wallet's rows, keys and
 * custody calls read it.
 */
// Route-scoped plugins can't wrap the rest of the pipeline in a coroutine context; `intercept` plus `proceed()` can.
@Suppress("DEPRECATION")
fun Route.walletNetwork(build: Route.() -> Unit): Route {
    val node =
        createChild(
            object : RouteSelector() {
                override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) =
                    RouteSelectorEvaluation.Transparent

                override fun toString() = "(wallet network)"
            },
        )
    node.intercept(ApplicationCallPipeline.Plugins) {
        val requested = call.request.headers[WALLET_NETWORK_HEADER]
        val gate = call.application.attributes.getOrNull(WalletNetworkGateKey)
        val resolved =
            if (gate == null) {
                Resource.success(Network.parse(requested) ?: Network.TESTNET)
            } else {
                gate.resolve(call.principal().userId, requested)
            }
        when (resolved) {
            is Resource.Error -> {
                call.respondError(resolved.error)
                finish()
            }

            is Resource.Success -> {
                call.attributes.put(CallNetworkKey, resolved.data)
                withContext(WalletNetwork(resolved.data)) { proceed() }
            }
        }
    }
    node.build()
    return node
}

/**
 * `authenticate(scopes) { walletNetwork { … } }` in one: a route group that needs a token and is about money on one
 * network. Every wallet, group money, PIN Vault and agent wallet route sits in one.
 */
fun Route.authenticateOnNetwork(vararg scopes: Scope, build: Route.() -> Unit): Route =
    authenticate(*scopes) { walletNetwork(build) }
