package com.circaso.identity.data

import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.testing.TestPostgres
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

private object ProbeNotesTable : Table("identityprobe.notes") {
    val id = javaUUID("id")
    val body = text("body")
    override val primaryKey = PrimaryKey(id)
}

/**
 * A module's data tests get PostgreSQL from infrastructure's test fixtures, as `infrastructure:api` types only
 * (decision 2026-10-27, PG1 review). Identity's own tables arrive in PG2; this probe shows the path works.
 */
class PostgresFixtureTest {
    private val spec = DatabaseModuleSpec("identityprobe", listOf(ProbeNotesTable), "classpath:db/identityprobe")

    @Test
    fun `a module's test migrates its schema, writes and reads through Database, and starts clean`() =
        runBlocking<Unit> {
            val database = TestPostgres.database(spec)
            TestPostgres.truncate("identityprobe")

            database.tx("notes.add") {
                ProbeNotesTable.insert {
                    it[id] = UUID.randomUUID()
                    it[body] = "hello"
                }
            }

            assertEquals(
                listOf("hello"),
                database.tx("notes.list") {
                    ProbeNotesTable.selectAll().toList().map { it[ProbeNotesTable.body] }
                },
            )
        }
}
