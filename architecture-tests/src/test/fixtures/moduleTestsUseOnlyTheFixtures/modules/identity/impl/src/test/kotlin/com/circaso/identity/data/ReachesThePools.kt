package com.circaso.identity.data

import com.circaso.infrastructure.impl.database.ModuleDatabase
import com.circaso.infrastructure.impl.database.PostgresPools
import com.circaso.infrastructure.testing.TestPostgres

// The fixtures put these on the classpath; a module's test must not build on them.
class ReachesThePools(private val pools: PostgresPools) {
    fun database() = ModuleDatabase("identity", pools)

    fun env() = TestPostgres.env()
}
