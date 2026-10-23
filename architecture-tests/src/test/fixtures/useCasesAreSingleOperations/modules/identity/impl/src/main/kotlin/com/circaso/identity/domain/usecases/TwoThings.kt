package com.circaso.identity.domain.usecases

import com.circaso.identity.data.repositories.ProfileRepositoryImpl

class ClaimHandle {
    suspend operator fun invoke() = Unit
}

class TwoThings(private val claim: ClaimHandle) {
    suspend operator fun invoke() = Unit
    suspend fun alsoThis() = Unit
}
