package com.circaso.identity.data

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.testing.TestPostgres

// Allowed: TestPostgres hands out infrastructure:api types only.
class UsesTheFixtures {
    fun database(spec: DatabaseModuleSpec): Database = TestPostgres.database(spec)
}
