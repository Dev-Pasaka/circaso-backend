package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.Staff
import com.circaso.ops.domain.usecases.AddStaff
import com.circaso.ops.domain.usecases.ListStaff
import com.circaso.ops.domain.usecases.UpdateStaff
import com.circaso.shared.Resource
import com.circaso.shared.StaffRole
import java.util.UUID

class StaffService(
    private val listStaff: ListStaff,
    private val addStaff: AddStaff,
    private val updateStaff: UpdateStaff,
) {
    suspend fun list(): Resource<List<Staff>> = listStaff()

    suspend fun add(
        actor: Actor,
        email: String,
        role: StaffRole,
    ): Resource<Staff> = addStaff(actor, email, role)

    suspend fun update(
        actor: Actor,
        staffId: UUID,
        role: StaffRole?,
        active: Boolean?,
    ): Resource<Staff> = updateStaff(actor, staffId, role, active)
}
