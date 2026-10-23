package com.circaso.identity.presentation.services

import com.circaso.identity.api.Principal
import com.circaso.identity.domain.models.RecoveryCommand
import com.circaso.identity.domain.models.SupportRecovery
import com.circaso.identity.domain.usecases.GetRecoveryCase
import com.circaso.identity.domain.usecases.RequestSupportRecovery
import com.circaso.shared.Resource
import java.util.UUID

/** Support-led recovery. */
class RecoveryService(
    private val requestSupportRecovery: RequestSupportRecovery,
    private val getRecoveryRequest: GetRecoveryCase,
) {
    suspend fun request(command: RecoveryCommand): Resource<SupportRecovery> = requestSupportRecovery(command)
    suspend fun get(principal: Principal, requestId: UUID): Resource<SupportRecovery> = getRecoveryRequest(principal, requestId)
}
