package com.circaso.identity.presentation.services

import com.circaso.identity.data.tables.AccountsTable
import org.jetbrains.exposed.v1.jdbc.selectAll

class AccountService {
    fun count() = AccountsTable.selectAll().count()
}
