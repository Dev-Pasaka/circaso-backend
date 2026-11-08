package com.circaso.identity.presentation.mappers

import com.circaso.identity.api.SupportAccountPage
import com.circaso.identity.api.SupportAccountView
import com.circaso.identity.domain.models.CreateSupportAccountCommand
import com.circaso.identity.domain.models.SupportAccount
import com.circaso.shared.Resource
import com.circaso.shared.SharedError
import com.circaso.identity.domain.models.SupportAccountPage as DomainSupportAccountPage

/** As long as a person's display name may be (users → U2). The handle's own rules are users', asked by the use case. */
private const val MAX_DISPLAY_NAME = 40

/** The shape checks OPS1 had on the app gateway, now on the `SupportAccounts` contract the ops module calls. */
fun supportAccountCommand(
    displayName: String,
    handle: String,
    createdBy: String,
): Resource<CreateSupportAccountCommand> {
    val name = displayName.trim()
    if (name.isEmpty() || name.length > MAX_DISPLAY_NAME) {
        return Resource.error(SharedError.Malformed("displayName", "must be 1 to $MAX_DISPLAY_NAME characters"))
    }
    if (handle.isBlank()) return Resource.error(SharedError.Malformed("handle", "is required"))
    if (createdBy.isBlank()) return Resource.error(SharedError.Malformed("createdBy", "is required"))
    return Resource.success(CreateSupportAccountCommand(createdBy.trim(), name, handle))
}

/**
 * The contract's name is never null; it is empty for the moment between the create and users making the profile from
 * `identity.SupportAccountCreated` (OPS2 lists it in that moment too).
 */
fun SupportAccount.toView() =
    SupportAccountView(
        userId = account.userId,
        displayName = displayName.orEmpty(),
        handle = handle,
        createdAt = account.createdAt,
    )

fun DomainSupportAccountPage.toView() = SupportAccountPage(accounts.map { it.toView() }, nextCursor)
