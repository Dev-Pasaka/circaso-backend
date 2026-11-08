package com.circaso.ops.presentation.api

import com.circaso.ops.api.StaffDirectory
import com.circaso.ops.api.StaffMember
import com.circaso.ops.domain.models.Staff
import com.circaso.ops.presentation.services.StaffDirectoryService
import com.circaso.shared.getOrNull

/** What the ops gateway asks on every staff request: is this verified email an active staff member, and with which role. */
class StaffDirectoryImpl(private val directory: StaffDirectoryService) : StaffDirectory {
    override suspend fun signIn(email: String): StaffMember? = directory.signIn(email).getOrNull()?.toMember()

    override suspend fun recordDevLogin(staff: StaffMember, requestId: String?) {
        val row = directory.signIn(staff.email).getOrNull() ?: return
        directory.devLogin(row, requestId)
    }

    private fun Staff.toMember() = StaffMember(id, email, role, active, fromConfig)
}
