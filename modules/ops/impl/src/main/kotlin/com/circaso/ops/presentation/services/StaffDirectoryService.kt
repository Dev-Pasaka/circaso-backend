package com.circaso.ops.presentation.services

import com.circaso.ops.domain.models.Staff
import com.circaso.ops.domain.usecases.RecordDevLogin
import com.circaso.ops.domain.usecases.SignInStaff
import com.circaso.shared.Resource

/** The ops gateway's questions, through `StaffDirectory`. */
class StaffDirectoryService(private val signInStaff: SignInStaff, private val recordDevLogin: RecordDevLogin) {
    suspend fun signIn(email: String): Resource<Staff?> = signInStaff(email)

    suspend fun devLogin(staff: Staff, requestId: String?): Resource<Unit> = recordDevLogin(staff, requestId)
}
