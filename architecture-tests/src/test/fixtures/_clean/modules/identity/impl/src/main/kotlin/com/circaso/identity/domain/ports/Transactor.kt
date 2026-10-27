package com.circaso.identity.domain.ports

interface Transactor {
    suspend fun <T> run(block: suspend () -> T): T
}
