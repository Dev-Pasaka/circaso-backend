package com.circaso.ops.presentation.dto

import kotlinx.serialization.Serializable

// The staff list and `/v1/ops/me`. Times are ISO-8601 UTC.

@Serializable
data class StaffDto(
    val id: String,
    val email: String,
    val role: String,
    val active: Boolean,
    val fromConfig: Boolean,
    val addedBy: String? = null,
    val createdAt: String,
    val updatedAt: String,
)

@Serializable
data class MeResponse(
    val staff: StaffDto,
    /** `ACCESS` or `DEV`; the portal shows a DEV LOGIN banner for the second. */
    val signInMethod: String,
    val environment: String,
    val permissions: List<String>,
)

@Serializable
data class StaffListResponse(val items: List<StaffDto>)

@Serializable
data class AddStaffRequest(val email: String, val role: String)

@Serializable
data class UpdateStaffRequest(val role: String? = null, val active: Boolean? = null)
