package com.circaso.identity.presentation.services

import com.circaso.identity.domain.usecases.GetAccount
import com.circaso.shared.UserId

class AccountService(private val getAccount: GetAccount) {
    suspend fun get(userId: UserId) = getAccount(userId)
}
