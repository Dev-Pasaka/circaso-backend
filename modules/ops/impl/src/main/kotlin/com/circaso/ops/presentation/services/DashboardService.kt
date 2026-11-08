package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Dashboard
import com.circaso.ops.domain.usecases.GetDashboard
import com.circaso.shared.Resource
import com.circaso.shared.StaffRole

class DashboardService(private val getDashboard: GetDashboard) {
    suspend fun get(role: StaffRole, days: Int): Resource<Dashboard> = getDashboard(role, days)
}
