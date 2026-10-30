package com.circaso.wallet.presentation.controllers

import com.circaso.shared.Scope
import com.circaso.shared.flatMap
import com.circaso.wallet.presentation.dto.AddChainRequest
import com.circaso.wallet.presentation.dto.AddChainResponse
import com.circaso.wallet.presentation.dto.AddMissingChainsResponse
import com.circaso.wallet.presentation.dto.ChainsResponse
import com.circaso.wallet.presentation.dto.CreateWalletResponse
import com.circaso.wallet.presentation.dto.SdkDto
import com.circaso.wallet.presentation.dto.WalletResponse
import com.circaso.wallet.presentation.mappers.createWalletCommand
import com.circaso.wallet.presentation.mappers.toCommand
import com.circaso.wallet.presentation.mappers.toDto
import com.circaso.wallet.presentation.mappers.toResponse
import com.circaso.wallet.presentation.mappers.walletQuery
import com.circaso.wallet.presentation.services.WalletService
import com.circaso.web.NoBody
import com.circaso.web.authenticate
import com.circaso.web.docs
import com.circaso.web.principal
import com.circaso.web.respondDefault
import com.circaso.web.toDefaultResponse
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route

private const val TAG = "Wallet"
private const val SCOPE = "Scope: `app`."
private const val CUSTODY = "wallet.custody_unavailable"

/** The wallet itself (W1–W3) and the chains it is on (W12). */
fun Route.walletController(wallets: WalletService) {
    route("/wallet") {
        authenticate(Scope.APP) {
            get {
                val result = walletQuery(call.principal().userId, call.request.queryParameters["currency"])
                call.respondDefault(result.flatMap { wallets.wallet(it) }.toDefaultResponse { it.toResponse() })
            }.docs<NoBody, WalletResponse>(
                "W1 · Your wallet: status, each chain with its address and assets, totals, and the fiat value",
                TAG,
                listOf(CUSTODY, "request.malformed"),
                notes =
                    "$SCOPE `status` is `NONE` before you create a wallet, `SETTING_UP` until you have set the PIN " +
                        "in the Circle SDK, then `ACTIVE`. Query `currency` (ISO 4217, the person's own currency, " +
                        "for example `KES`) adds `fiat`: what the US dollar stablecoins are worth at USDC = 1 USD. " +
                        "`fiat` is null when no rate is known (never an error) and `partial` is true when something " +
                        "held has no price yet. A code that isn't ISO 4217 answers `request.malformed`. Show " +
                        "\"Rates by Exchange Rate API\" wherever the fiat value appears.",
            )

            post {
                val command = createWalletCommand(call.principal().userId, call.idempotencyKey())
                call.respondDefault(wallets.create(command).toDefaultResponse { it.toResponse() })
            }.docs<NoBody, CreateWalletResponse>(
                "W2 · Create your wallet: the challenge that sets the PIN and creates it on the default chain",
                TAG,
                listOf("wallet.already_exists", "wallet.support_account_has_no_wallet", CUSTODY),
                notes =
                    "$SCOPE Run `challengeId` in the Circle SDK with `sdk`. Calling again while `SETTING_UP` gives " +
                        "a fresh challenge; `challengeId` is null when the PIN is already set and the wallet is on " +
                        "its way (poll W1). Once `ACTIVE`: `wallet.already_exists`.",
            )

            post("session") {
                val result = wallets.session(call.principal().userId)
                call.respondDefault(result.toDefaultResponse { it.toDto() })
            }.docs<NoBody, SdkDto>(
                "W3 · The Circle SDK's session values",
                TAG,
                listOf("wallet.not_found", CUSTODY),
                notes =
                    "$SCOPE `appId`, `userToken` and `encryptionKey` for the Circle SDK, valid until `expiresAt` " +
                        "(an hour at most). Keep them in memory only.",
            )

            route("/chains") {
                get {
                    val result = wallets.chains(call.principal().userId)
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<NoBody, ChainsResponse>(
                    "W12 · The supported chains, and which your wallet is on",
                    TAG,
                    notes =
                        "$SCOPE Each chain is `ENABLED`, `ADDING` (asked for, not signed yet) or `AVAILABLE`. " +
                            "`defaultChain` is where a new wallet starts. A chain retired from receiving " +
                            "(`receiveEnabled: false`) is listed only while the wallet is on it, so it can be emptied.",
                )

                post {
                    val userId = call.principal().userId
                    val result = call.receive<AddChainRequest>().toCommand(userId).flatMap { wallets.addChain(it) }
                    call.respondDefault(result.toDefaultResponse { it.toResponse() })
                }.docs<AddChainRequest, AddChainResponse>(
                    "W12 · Add a supported chain to your wallet",
                    TAG,
                    listOf(
                        "wallet.chain_unsupported",
                        "wallet.not_found",
                        "wallet.not_ready",
                        "wallet.support_account_has_no_wallet",
                        CUSTODY,
                        "request.malformed",
                    ),
                    notes =
                        "$SCOPE Answers `ADDING` with a challenge to run in the Circle SDK (the PIN creates the " +
                            "wallet on that chain), or `ENABLED` with no challenge when the wallet is already on " +
                            "it. Circle is asked first every time, and adds of the same chain within the hour get " +
                            "the same challenge, so a chain is never made twice. A chain retired from receiving " +
                            "can't be added (`wallet.chain_unsupported`). The new chain shows in W1 once Circle has " +
                            "made it.",
                )

                post("missing") {
                    val result = wallets.addMissingChains(call.principal().userId)
                    call.respondDefault(
                        result.toDefaultResponse { list -> AddMissingChainsResponse(list.map { it.toResponse() }) },
                    )
                }.docs<NoBody, AddMissingChainsResponse>(
                    "W13 · Add every supported chain the wallet isn't on",
                    TAG,
                    listOf("wallet.not_found", "wallet.not_ready", "wallet.support_account_has_no_wallet", CUSTODY),
                    notes =
                        "$SCOPE One entry per chain added, each `ADDING` with the challenge to run in the " +
                            "Circle SDK. " +
                            "Chains that share a challenge (Circle: the same account type) carry the same " +
                            "`challengeId`: run each distinct challenge once, so the PIN is asked once per group. " +
                            "An empty list means the wallet is already on every supported chain.",
                )
            }
        }
    }
}

/** The request's `Idempotency-Key`; the gateway refuses a state change without one before it gets here. */
internal fun ApplicationCall.idempotencyKey(): String = request.headers["Idempotency-Key"].orEmpty()
