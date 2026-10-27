package com.circaso.identity.data.repositories

import com.circaso.identity.data.tables.AccountsTable
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction

class RawRepositoryImpl {
    private val db = Database.connect("r2dbc:postgresql://localhost/circaso")
    private val other = R2dbcDatabase.connect("r2dbc:postgresql://localhost/circaso")

    suspend fun count() = suspendTransaction(db) { AccountsTable.selectAll().count() }

    suspend fun create() = suspendTransaction { SchemaUtils.create(AccountsTable) }
}
