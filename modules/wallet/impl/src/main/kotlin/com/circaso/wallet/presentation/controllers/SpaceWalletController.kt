package com.circaso.wallet.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.wallet.presentation.dto.SpaceWalletResponse
import com.circaso.wallet.presentation.dto.SpaceWalletSetupResponse
import com.circaso.wallet.presentation.mappers.createSpaceWalletCommand
import com.circaso.wallet.presentation.mappers.spaceIdOf
import com.circaso.wallet.presentation.mappers.toResponse
import com.circaso.wallet.presentation.services.SpaceWalletService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

/** A space's own wallet (Spaces phase 3): W18 set it up, W19 read where it stands. */
fun Route.spaceWalletController(spaceWallets: SpaceWalletService) {
    route("/wallet/spaces/{spaceId}") {
        authenticate(Scope.APP) {
            post {
                val userId = call.principal().userId
                val key = call.idempotencyKey()
                val result =
                    spaceIdOf(call.parameters["spaceId"]).flatMap {
                        spaceWallets.create(createSpaceWalletCommand(userId, it, key))
                    }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, SpaceWalletSetupResponse>(
                "W18 · Set up the space's wallet: the challenge that sets its own PIN",
                "Wallet",
                listOf(
                    "wallet.space_not_found",
                    "wallet.not_space_owner",
                    "wallet.not_signer",
                    "wallet.already_exists",
                    "wallet.account_restricted",
                    "wallet.support_account_has_no_wallet",
                    "wallet.custody_unavailable",
                    "request.malformed",
                ),
                notes =
                    "Scope: `app`. The space's owner only (SN4). The space wallet is its own Circle user, so its own " +
                        "PIN and biometrics: run `challengeId` with this answer's `sdk`, naming the space on the PIN " +
                        "screens, then W13 with `?walletId=` puts it on every network. You become its signer " +
                        "and send from it directly. Again while `SETTING_UP`: a fresh challenge (Finish Wallet " +
                        "Setup). When it " +
                        "turns active, the space goes live (`spaces/space.updated`) and the owner and admins get " +
                        "`wallet/wallet.ready` with `walletId`.",
            )

            get {
                val userId = call.principal().userId
                val result = spaceIdOf(call.parameters["spaceId"]).flatMap { spaceWallets.get(userId, it) }
                call.respondDefault(result.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, SpaceWalletResponse>(
                "W19 · The space's wallet: none, being set up, or active",
                "Wallet",
                listOf("wallet.space_not_found", "request.malformed"),
                notes =
                    "Scope: `app`. The owner and admins only. Poll it every 3 seconds during set-up: while " +
                        "`SETTING_UP` the server reads Circle at most once every few seconds, so it turns `ACTIVE` " +
                        "on the first poll after the PIN. The balance is W1 with `?walletId=`.",
            )
        }
    }
}
