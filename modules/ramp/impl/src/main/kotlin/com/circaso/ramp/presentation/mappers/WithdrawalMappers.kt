package com.circaso.ramp.presentation.mappers

import com.circaso.ramp.domain.models.DestinationBook
import com.circaso.ramp.domain.models.DestinationCommand
import com.circaso.ramp.domain.models.SavedDestination
import com.circaso.ramp.domain.models.WithdrawCommand
import com.circaso.ramp.domain.models.Withdrawal
import com.circaso.ramp.domain.models.WithdrawalDestination
import com.circaso.ramp.domain.models.WithdrawalPage
import com.circaso.ramp.domain.support.Amounts
import com.circaso.ramp.domain.support.WithdrawalExplorer
import com.circaso.ramp.presentation.dto.DestinationRequest
import com.circaso.ramp.presentation.dto.DestinationResponse
import com.circaso.ramp.presentation.dto.DestinationsResponse
import com.circaso.ramp.presentation.dto.WithdrawRequest
import com.circaso.ramp.presentation.dto.WithdrawalDestinationDto
import com.circaso.ramp.presentation.dto.WithdrawalLimitsDto
import com.circaso.ramp.presentation.dto.WithdrawalResponse
import com.circaso.ramp.presentation.dto.WithdrawalsResponse
import com.circaso.shared.Resource
import java.time.Instant
import java.util.UUID

private const val WITHDRAW_PIN_LENGTH = 6
private const val MAX_LABEL = 40
private const val MAX_ADDRESS = 100

/** A 6-digit PIN, or `request.malformed` on field `pin`. */
fun pinOf(raw: String): Resource<String> {
    val pin = raw.trim()
    return if (pin.length == WITHDRAW_PIN_LENGTH && pin.all { it in '0'..'9' }) {
        Resource.success(pin)
    } else {
        malformed("pin", "must be 6 digits")
    }
}

/** An id from the path or the body, or `request.malformed` on [field]. */
fun idOf(raw: String?, field: String): Resource<UUID> =
    runCatching { UUID.fromString(raw?.trim()) }.getOrNull()?.let { Resource.success(it) }
        ?: malformed(field, "not an id")

fun DestinationRequest.toCommand(): Resource<DestinationCommand> {
    val name = label.trim()
    if (name.isEmpty() || name.length > MAX_LABEL) return malformed("label", "1 to $MAX_LABEL characters")
    val raw = address.trim()
    if (raw.isEmpty() || raw.length > MAX_ADDRESS) return malformed("address", "required")
    return when (val code = pinOf(pin)) {
        is Resource.Error -> code
        is Resource.Success -> Resource.success(DestinationCommand(raw, name, code.data, passkey?.toAssertion()))
    }
}

fun WithdrawRequest.toCommand(idempotencyKey: String?): Resource<WithdrawCommand> {
    val amount =
        amountMinor.trim().toLongOrNull()?.takeIf { it > 0 }
            ?: return malformed("amountMinor", "USDC minor units as digits")
    val destination =
        when (val id = idOf(destinationId, "destinationId")) {
            is Resource.Error -> return id
            is Resource.Success -> id.data
        }
    return when (val code = pinOf(pin)) {
        is Resource.Error -> {
            code
        }

        is Resource.Success -> {
            Resource.success(WithdrawCommand(amount, destination, code.data, idempotencyKey, passkey?.toAssertion()))
        }
    }
}

fun WithdrawalDestination.toResponse(now: Instant): DestinationResponse = toResponse(isUsableAt(now))

private fun WithdrawalDestination.toResponse(usable: Boolean): DestinationResponse =
    DestinationResponse(
        destinationId = destinationId.toString(),
        label = label,
        address = address,
        blockchain = blockchain,
        network = network.name,
        createdAt = createdAt.toString(),
        usableAt = usableAt.toString(),
        usable = usable,
        frozen = frozenAt != null,
    )

fun SavedDestination.toResponse(): DestinationResponse = destination.toResponse(usable)

fun DestinationBook.toResponse(): DestinationsResponse =
    DestinationsResponse(destinations.map { it.toResponse(now) }, delayHours, blockchain, max)

fun Withdrawal.toResponse(): WithdrawalResponse =
    WithdrawalResponse(
        withdrawalId = withdrawalId.toString(),
        state = state.name,
        amount = Amounts.usdc(amountMinor),
        network = network.name,
        blockchain = blockchain,
        destination = WithdrawalDestinationDto(destinationId.toString(), destinationLabel, destinationAddress),
        txHash = txHash,
        explorerUrl = WithdrawalExplorer.url(blockchain, txHash),
        failureReason = failureReason,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString(),
        sentAt = sentAt?.toString(),
        closedAt = closedAt?.toString(),
    )

fun WithdrawalPage.toResponse(): WithdrawalsResponse =
    WithdrawalsResponse(
        withdrawals = withdrawals.map { it.toResponse() },
        limits =
            WithdrawalLimitsDto(
                min = Amounts.usdc(minMinor),
                dailyMax = dailyMaxMinor?.let { Amounts.usdc(it) },
                usedToday = Amounts.usdc(usedTodayMinor),
            ),
        next = next?.withdrawalId?.toString(),
    )
