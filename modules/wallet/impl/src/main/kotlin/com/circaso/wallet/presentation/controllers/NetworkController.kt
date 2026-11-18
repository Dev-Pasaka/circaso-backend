package com.circaso.wallet.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.wallet.presentation.dto.NetworksResponse
import com.circaso.wallet.presentation.mappers.toResponse
import com.circaso.wallet.presentation.services.NetworkService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route

/** W20: the wallet networks the caller may use (decision 2026-11-18). */
fun Route.networkController(networks: NetworkService) {
    route("/wallet/networks") {
        // Not inside `walletNetwork { }`: this is the call that tells the app which networks it may name.
        authenticate(Scope.APP) {
            get {
                val result = networks.networks(call.principal().userId)
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, NetworksResponse>(
                "W20 · The wallet networks you may use, and where your wallet on each stands",
                "Wallet",
                notes =
                    "Scope: `app`. Takes no `X-Wallet-Network`. `networks` lists only the networks you may use, " +
                        "mainnet first, each with `walletStatus` (`NONE`, `SETTING_UP` or `ACTIVE`, as W1's " +
                        "`status`); `default` is `MAINNET` when you may use it, else `TESTNET`. Show the " +
                        "Mainnet | Testnet switch only when there is more than one entry. Send the chosen one as " +
                        "`X-Wallet-Network` on every wallet, group money, PIN Vault and Asha request: a network " +
                        "you may not use answers `wallet.network_unavailable` (403). The mainnet wallet is a " +
                        "separate wallet with its own PIN, addresses, history, limits and PIN Vault.",
            )
        }
    }
}
