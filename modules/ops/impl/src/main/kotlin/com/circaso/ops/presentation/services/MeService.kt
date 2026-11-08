package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.usecases.ListPermissions
import com.circaso.shared.Resource
import com.circaso.shared.StaffRole

class MeService(private val listPermissions: ListPermissions) {
    suspend fun permissions(role: StaffRole): Resource<List<Permission>> = listPermissions(role)
}
