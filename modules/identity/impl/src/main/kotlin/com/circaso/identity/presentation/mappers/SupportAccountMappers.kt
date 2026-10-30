package com.circaso.identity.presentation.mappers

import com.circaso.identity.domain.models.CreateSupportAccountCommand
import com.circaso.identity.domain.models.SupportAccount
import com.circaso.identity.domain.models.SupportAccountPage
import com.circaso.identity.presentation.dto.CreateSupportAccountRequest
import com.circaso.identity.presentation.dto.SupportAccountDto
import com.circaso.identity.presentation.dto.SupportAccountsResponse
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.web.CallPrincipal

/** As long as a person's display name may be (users → U2). The handle's own rules are users', asked by the use case. */
private const val MAX_DISPLAY_NAME = 40

fun CreateSupportAccountRequest.toCommand(caller: CallPrincipal): Resource<CreateSupportAccountCommand> {
    val name = displayName.trim()
    if (name.isEmpty() || name.length > MAX_DISPLAY_NAME) {
        return Resource.error(SharedError.Malformed("displayName", "must be 1 to $MAX_DISPLAY_NAME characters"))
    }
    if (handle.isBlank()) return Resource.error(SharedError.Malformed("handle", "is required"))
    return Resource.success(CreateSupportAccountCommand(caller.userId, name, handle))
}

fun SupportAccount.toDto() =
    SupportAccountDto(
        userId = account.userId.toString(),
        displayName = displayName,
        handle = handle,
        kind = account.kind.name,
        status = account.status.name,
        createdAt = account.createdAt,
    )

fun SupportAccountPage.toResponse() = SupportAccountsResponse(accounts.map { it.toDto() }, nextCursor)
