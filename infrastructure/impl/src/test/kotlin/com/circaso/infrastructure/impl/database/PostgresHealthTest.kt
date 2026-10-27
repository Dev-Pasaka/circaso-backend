package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.impl.TestPostgres
import com.circaso.shared.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertTrue

/** Readiness over R2DBC, gated on the background start (pools open, migrations current). */
class PostgresHealthTest {
    private val pools = PostgresPools(TestPostgres.settings())
    private val scope = CoroutineScope(SupervisorJob())

    @AfterTest
    fun close() {
        scope.cancel()
        pools.close()
    }

    @Test
    fun `ready once the pools are open and every module is migrated`() =
        runBlocking<Unit> {
            val startup = PostgresStartup().apply { begin(scope) { pools.open() } }

            assertTrue(PostgresHealth(pools, startup).check() is Resource.Success)
        }

    @Test
    fun `not ready while a module's migrations are missing`() =
        runBlocking<Unit> {
            val unmigrated = DatabaseModuleSpec("pgnever", emptyList(), "classpath:db/pgtx")
            val startup =
                PostgresStartup().apply {
                    begin(scope) {
                        pools.open()
                        MigrationHistory(pools).requireCurrent(listOf(unmigrated))
                    }
                }

            assertTrue(PostgresHealth(pools, startup).check() is Resource.Error)
        }

    @Test
    fun `not ready before the start has begun`() =
        runBlocking<Unit> {
            assertTrue(PostgresHealth(pools, PostgresStartup()).check() is Resource.Error)
        }
}
