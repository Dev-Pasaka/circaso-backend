package com.circaso.users.data.repositories

import com.circaso.identity.data.tables.AccountsTable
import org.jetbrains.exposed.v1.jdbc.selectAll

// Users reading identity's table: the database role would refuse it too.
class PeekRepositoryImpl {
    fun accounts() = AccountsTable.selectAll().count()
}
