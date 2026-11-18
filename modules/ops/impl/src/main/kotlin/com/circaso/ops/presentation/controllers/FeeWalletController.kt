package com.circaso.ops.presentation.controllers

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.support.OpsSettings
import com.circaso.ops.presentation.dto.AddFeeDestinationRequest
import com.circaso.ops.presentation.dto.CreateFeeWithdrawalRequest
import com.circaso.ops.presentation.dto.FeeDestinationDto
import com.circaso.ops.presentation.dto.FeeDestinationsResponse
import com.circaso.ops.presentation.dto.FeeWalletsResponse
import com.circaso.ops.presentation.dto.FeeWithdrawalDto
import com.circaso.ops.presentation.dto.FeeWithdrawalsResponse
import com.circaso.ops.presentation.mappers.FORBIDDEN
import com.circaso.ops.presentation.mappers.MALFORMED
import com.circaso.ops.presentation.mappers.PAGED
import com.circaso.ops.presentation.mappers.TAG_FEE_WALLETS
import com.circaso.ops.presentation.mappers.actor
import com.circaso.ops.presentation.mappers.malformed
import com.circaso.ops.presentation.mappers.pageLimit
import com.circaso.ops.presentation.mappers.parseUuid
import com.circaso.ops.presentation.mappers.query
import com.circaso.ops.presentation.mappers.roles
import com.circaso.ops.presentation.mappers.toDestination
import com.circaso.ops.presentation.mappers.toDto
import com.circaso.ops.presentation.mappers.toResponse
import com.circaso.ops.presentation.mappers.toWithdrawal
import com.circaso.ops.presentation.services.FeeWalletService
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.shared.networkFilter
import com.circaso.web.NoBody
import com.circaso.web.docs
import com.circaso.web.respondDefault
import com.circaso.web.staffOnly
import com.circaso.web.toDefaultResponse
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.route
import java.time.Clock

private const val REASON = "ops.reason_required"
private const val IDEMPOTENCY_KEY = "Idempotency-Key"

/**
 * Circaso's fee wallets (decision 2026-11-18): Circle developer-controlled wallets this server creates itself at
 * start-up (there is no create route), the destinations collected fees may be withdrawn to, and the withdrawals.
 * FINANCE and ADMIN read (`fees.wallet.read`); ADMIN adds and removes destinations and withdraws (`fees.withdraw`).
 */
fun Route.feeWalletController(
    feeWallets: FeeWalletService,
    settings: OpsSettings,
    clock: Clock,
) {
    route("/ops/fee-wallets") {
        staffOnly(Permission.FEES_WALLET_READ.roles) { feeWalletReads(feeWallets, settings, clock) }
        staffOnly(Permission.FEES_WITHDRAW.roles) { feeWalletWrites(feeWallets, clock) }
    }
}

private fun Route.feeWalletReads(
    feeWallets: FeeWalletService,
    settings: OpsSettings,
    clock: Clock,
) {
    get {
        val network = networkFilter()
        val result =
            if (network == null) {
                malformed("network", "is required: MAINNET or TESTNET")
            } else {
                feeWallets.wallets(network)
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, FeeWalletsResponse>(
        "OPS · A network's fee wallets and what they hold",
        TAG_FEE_WALLETS,
        listOf(MALFORMED, FORBIDDEN),
        "${roles("FINANCE", "ADMIN")} `?network=MAINNET|TESTNET` is required. `status`: READY (every chain that " +
            "can carry a fee has a fee wallet, and fees are collected), NOT_CREATED (some chain has none, and " +
            "nothing went wrong: no key or no entity secret on this server), FAILED (creating one was tried and " +
            "Circle refused or couldn't be reached); `reason` says why, or what a READY network can't do. The server " +
            "creates the wallets itself when it starts and checks again every ten minutes; there is nothing to " +
            "press. Per chain: the address fees are charged to, `balances` (every offered coin, at zero when " +
            "none was collected, then anything else that arrived), `nativeBalance` (the chain's own coin, which " +
            "pays a withdrawal's network fee: top it up before withdrawing), all in minor units as text, read from " +
            "Circle on each call. `balancesKnown: false`: Circle couldn't be asked, `balances` is empty and " +
            "`nativeBalance` null. `withdrawable: false` comes with `unavailableReason`.",
    )

    get("destinations") {
        val result = feeWallets.destinations(networkFilter())
        val now = clock.instant()
        call.respondDefault(
            result.toDefaultResponse { all -> FeeDestinationsResponse(all.map { it.toDto(now) }) },
        )
    }.docs<NoBody, FeeDestinationsResponse>(
        "OPS · Where collected fees may be withdrawn to",
        TAG_FEE_WALLETS,
        listOf(MALFORMED, FORBIDDEN),
        "${roles("FINANCE", "ADMIN")} `?network=` narrows to one network; without it, both. Newest first. " +
            "`usableAt` is `createdAt` plus the delay (`destinationDelayHours` on the fee wallets, 24 unless " +
            "changed in the database): until then a withdrawal to it answers `fees.destination_not_ready`.",
    )

    get("withdrawals") {
        val result =
            call.pageLimit(settings).flatMap { limit ->
                feeWallets.withdrawals(networkFilter(), call.query("cursor"), limit)
            }
        call.respondDefault(result.toDefaultResponse { it.toResponse() })
    }.docs<NoBody, FeeWithdrawalsResponse>(
        "OPS · Withdrawals of collected fees",
        TAG_FEE_WALLETS,
        listOf(MALFORMED, FORBIDDEN),
        "${roles("FINANCE", "ADMIN")} `?network=` narrows to one network. Newest first. $PAGED (at most 100 here.) " +
            "`state`: PENDING (recorded; Circle isn't known to have it yet, and it is sent as soon as Circle can " +
            "be reached, once), SENT (Circle has it), CONFIRMED (on the chain, with `txHash`), FAILED (nothing " +
            "left the wallet; `failureReason` says why). The server follows each one every 15 seconds.",
    )
}

private fun Route.feeWalletWrites(feeWallets: FeeWalletService, clock: Clock) {
    post("destinations") {
        val request = call.receive<AddFeeDestinationRequest>()
        val result =
            request.toDestination().flatMap { feeWallets.addDestination(call.actor(), it, request.reason) }
        val now = clock.instant()
        call.respondDefault(result.toDefaultResponse { it.toDto(now) })
    }.docs<AddFeeDestinationRequest, FeeDestinationDto>(
        "OPS · Add a withdrawal destination",
        TAG_FEE_WALLETS,
        listOf(
            REASON,
            "fees.invalid",
            "fees.destination_is_fee_wallet",
            "fees.destination_exists",
            "fees.custody_unavailable",
            MALFORMED,
            FORBIDDEN,
        ),
        "${roles("ADMIN")} One address on one chain that carries fees (EVM chains), with a label (1 to 80 " +
            "characters) and a reason (audited). The address is checked with Circle for that chain; one of " +
            "Circaso's own fee wallets is refused. It can be withdrawn to only from `usableAt`, so an address " +
            "added by mistake can be removed first.",
    )

    delete("destinations/{id}") {
        val result =
            parseUuid(call.parameters["id"], "id").flatMap { id ->
                feeWallets.removeDestination(call.actor(), id, call.query("reason"))
            }
        val now = clock.instant()
        call.respondDefault(result.toDefaultResponse { it.toDto(now) })
    }.docs<NoBody, FeeDestinationDto>(
        "OPS · Remove a withdrawal destination",
        TAG_FEE_WALLETS,
        listOf("fees.destination_not_found", MALFORMED, FORBIDDEN),
        "${roles("ADMIN")} Audited; `?reason=` is kept when given. Answers the destination as it was. " +
            "Withdrawals already made to it keep naming it; adding the address again starts the delay again.",
    )

    post("withdrawals") {
        val request = call.receive<CreateFeeWithdrawalRequest>()
        val key =
            call.request.headers[IDEMPOTENCY_KEY]
                ?.trim()
                .orEmpty()
        val result =
            if (key.isEmpty()) {
                malformed(IDEMPOTENCY_KEY, "is required")
            } else {
                request.toWithdrawal(key).flatMap { feeWallets.withdraw(call.actor(), it, request.reason) }
            }
        call.respondDefault(result.toDefaultResponse { it.toDto() })
    }.docs<CreateFeeWithdrawalRequest, FeeWithdrawalDto>(
        "OPS · Withdraw collected fees",
        TAG_FEE_WALLETS,
        listOf(
            REASON,
            "fees.destination_not_ready",
            "fees.insufficient_balance",
            "fees.insufficient_gas",
            "fees.destination_not_found",
            "fees.destination_is_fee_wallet",
            "fees.asset_unsupported",
            "fees.withdrawals_unavailable",
            "fees.withdrawal_refused",
            "fees.custody_unavailable",
            "fees.invalid",
            MALFORMED,
            FORBIDDEN,
        ),
        "${roles("ADMIN")} Sends `amountMinor` (the integer amount in the coin's smallest unit, as text) of " +
            "`asset` (a coin offered on the chain, or the chain's own coin) from the chain's fee wallet to a " +
            "destination added at least the delay ago. The same `Idempotency-Key` from the same staff member is " +
            "the same withdrawal, never a second one. Checked first: the destination is usable, the wallet holds " +
            "the amount (counting withdrawals on their way) and enough of the chain's own coin for the network " +
            "fee (Circle's estimate). Answers the withdrawal as it stands: SENT when Circle took it, PENDING when " +
            "Circle couldn't be reached (it is sent once, as soon as it can be). A refusal by Circle answers " +
            "`fees.withdrawal_refused` and leaves a FAILED withdrawal in the list. Audited with who, why, the " +
            "network, the chain, the amount and the destination.",
    )
}
