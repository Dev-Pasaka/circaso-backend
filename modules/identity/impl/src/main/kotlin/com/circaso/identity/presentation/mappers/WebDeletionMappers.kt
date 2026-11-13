package com.circaso.identity.presentation.mappers

import com.circaso.identity.domain.errors.IdentityError
import com.circaso.identity.domain.models.LeavingReason
import com.circaso.identity.domain.models.WebDeletionCommand
import com.circaso.identity.domain.models.WebDeletionOutcome
import com.circaso.identity.presentation.dto.WebDeletionRequest
import com.circaso.identity.presentation.dto.WebDeletionResponse
import com.circaso.shared.Resource
import com.circaso.shared.SharedError

fun WebDeletionRequest.toCommand(): Resource<WebDeletionCommand> {
    val provider = parseProvider(provider) ?: return Resource.error(SharedError.Malformed("provider", "is not valid"))
    if (idToken.isBlank()) return Resource.error(SharedError.Malformed("idToken", "is required"))
    val nonce = nonce?.trim()?.takeIf { it.isNotEmpty() } ?: return Resource.error(IdentityError.NonceInvalid)
    val reason = LeavingReason.of(reason) ?: return Resource.error(SharedError.Malformed("reason", "is not valid"))
    val details = details?.trim()?.takeIf { it.isNotEmpty() }
    if (details != null && details.length > WebDeletionCommand.MAX_DETAILS) {
        return Resource.error(
            SharedError.Malformed("details", "is longer than ${WebDeletionCommand.MAX_DETAILS} characters"),
        )
    }
    return Resource.success(WebDeletionCommand(provider, idToken.trim(), nonce, reason, details))
}

fun WebDeletionOutcome.toResponse(): WebDeletionResponse =
    when (this) {
        WebDeletionOutcome.NoAccount -> WebDeletionResponse("no_account")
        is WebDeletionOutcome.Scheduled -> WebDeletionResponse("scheduled", deleteAfter.toString())
        is WebDeletionOutcome.AlreadyScheduled -> WebDeletionResponse("already_scheduled", deleteAfter?.toString())
        is WebDeletionOutcome.Refused -> WebDeletionResponse("refused", code = code, message = message)
    }
