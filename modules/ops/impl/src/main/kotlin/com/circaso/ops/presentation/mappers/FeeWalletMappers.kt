package com.circaso.ops.presentation.mappers

import com.circaso.ops.presentation.dto.AddFeeDestinationRequest
import com.circaso.ops.presentation.dto.CreateFeeWithdrawalRequest
import com.circaso.ops.presentation.dto.FeeDestinationDto
import com.circaso.ops.presentation.dto.FeeWalletBalanceDto
import com.circaso.ops.presentation.dto.FeeWalletDto
import com.circaso.ops.presentation.dto.FeeWalletsResponse
import com.circaso.ops.presentation.dto.FeeWithdrawalDestinationDto
import com.circaso.ops.presentation.dto.FeeWithdrawalDto
import com.circaso.ops.presentation.dto.FeeWithdrawalsResponse
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.flatMap
import com.circaso.wallet.api.OpsFeeBalance
import com.circaso.wallet.api.OpsFeeDestination
import com.circaso.wallet.api.OpsFeeWallets
import com.circaso.wallet.api.OpsFeeWithdrawal
import com.circaso.wallet.api.OpsFeeWithdrawalPage
import com.circaso.wallet.api.OpsNewFeeDestination
import com.circaso.wallet.api.OpsNewFeeWithdrawal
import java.math.BigInteger
import java.time.Instant

const val TAG_FEE_WALLETS = "Ops · Fee wallets"

/** An amount in minor units is digits only: no sign, no point, no exponent, at most a uint256's 78 digits. */
private val MINOR_AMOUNT = Regex("^[0-9]{1,78}$")

fun parseNetwork(raw: String?, field: String = "network"): Resource<Network> =
    Network.parse(raw?.trim()?.uppercase())?.let { Resource.success(it) }
        ?: malformed(field, "must be MAINNET or TESTNET")

fun parseMinorAmount(raw: String, field: String = "amountMinor"): Resource<BigInteger> {
    val text = raw.trim()
    if (!MINOR_AMOUNT.matches(text)) return malformed(field, "must be a whole number of minor units, as text")
    val amount = BigInteger(text)
    return if (amount.signum() > 0) Resource.success(amount) else malformed(field, "must be above zero")
}

private fun OpsFeeBalance.toDto() = FeeWalletBalanceDto(symbol, amountMinor.toString(), decimals)

fun OpsFeeWallets.toResponse() =
    FeeWalletsResponse(
        network = network.name,
        status = status,
        reason = reason,
        wallets =
            wallets.map {
                FeeWalletDto(
                    blockchain = it.blockchain,
                    address = it.address,
                    balances = it.balances.map { balance -> balance.toDto() },
                    nativeBalance = it.nativeBalance?.toDto(),
                    balancesKnown = it.balancesKnown,
                    withdrawable = it.withdrawable,
                    unavailableReason = it.unavailableReason,
                )
            },
        destinationDelayHours = destinationDelayHours,
    )

fun OpsFeeDestination.toDto(now: Instant) =
    FeeDestinationDto(
        id = id.toString(),
        network = network.name,
        blockchain = blockchain,
        address = address,
        label = label,
        createdBy = createdBy,
        createdById = createdById.toString(),
        createdAt = createdAt.toString(),
        usableAt = usableAt.toString(),
        usable = !now.isBefore(usableAt),
    )

fun OpsFeeWithdrawal.toDto() =
    FeeWithdrawalDto(
        id = id.toString(),
        network = network.name,
        blockchain = blockchain,
        asset = asset,
        amountMinor = amountMinor.toString(),
        decimals = decimals,
        destination = FeeWithdrawalDestinationDto(destinationId.toString(), destinationAddress, destinationLabel),
        state = state,
        txHash = txHash,
        reason = reason,
        failureReason = failureReason,
        by = by,
        byId = byId.toString(),
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
    )

fun OpsFeeWithdrawalPage.toResponse() = FeeWithdrawalsResponse(items.map { it.toDto() }, nextCursor)

fun AddFeeDestinationRequest.toDestination(): Resource<OpsNewFeeDestination> =
    parseNetwork(network).flatMap { Resource.success(OpsNewFeeDestination(it, blockchain, address, label)) }

fun CreateFeeWithdrawalRequest.toWithdrawal(idempotencyKey: String): Resource<OpsNewFeeWithdrawal> =
    parseNetwork(network).flatMap { on ->
        parseMinorAmount(amountMinor).flatMap { amount ->
            parseUuid(destinationId, "destinationId").flatMap { destination ->
                Resource.success(
                    OpsNewFeeWithdrawal(
                        network = on,
                        blockchain = blockchain,
                        asset = asset,
                        amountMinor = amount,
                        destinationId = destination,
                        reason = reason,
                        idempotencyKey = idempotencyKey,
                    ),
                )
            }
        }
    }
