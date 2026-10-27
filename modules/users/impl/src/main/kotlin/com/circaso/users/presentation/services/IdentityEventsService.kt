package com.circaso.users.presentation.services

import com.circaso.identity.api.AccountActivated
import com.circaso.identity.api.AccountDeleted
import com.circaso.identity.api.AccountStatusChanged
import com.circaso.identity.api.PhoneChanged
import com.circaso.shared.Resource
import com.circaso.users.domain.usecases.ApplyAccountStatus
import com.circaso.users.domain.usecases.CreateProfileOnActivation
import com.circaso.users.domain.usecases.DeleteUserData
import com.circaso.users.domain.usecases.RecheckWaitingContacts
import java.time.Instant

/** Identity's events, which users reacts to. */
class IdentityEventsService(
    private val createProfileOnActivation: CreateProfileOnActivation,
    private val recheckWaitingContacts: RecheckWaitingContacts,
    private val applyAccountStatus: ApplyAccountStatus,
    private val deleteUserData: DeleteUserData,
) {
    suspend fun accountActivated(event: AccountActivated): Resource<Unit> = createProfileOnActivation(event)

    suspend fun phoneChanged(event: PhoneChanged): Resource<Unit> = recheckWaitingContacts(event)

    suspend fun statusChanged(event: AccountStatusChanged, occurredAt: Instant): Resource<Unit> =
        applyAccountStatus(event, occurredAt)

    suspend fun accountDeleted(event: AccountDeleted): Resource<Unit> = deleteUserData(event)
}
