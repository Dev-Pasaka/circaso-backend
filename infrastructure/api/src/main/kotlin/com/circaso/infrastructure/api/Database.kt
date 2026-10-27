package com.circaso.infrastructure.api

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import java.time.Duration

/**
 * What a module contributes to PostgreSQL: its schema (which is also the name of its database role), its Exposed
 * tables and where its Flyway scripts live. `app` collects every module's spec; the `Migrate` main creates the
 * schema and the role and runs Flyway once per spec, and `serve` checks that each spec's history is current.
 */
data class DatabaseModuleSpec(
    /** The module's name: `identity`, `users`. */
    val schema: String,
    /** Every table the module owns, each named `<schema>.<table>`. */
    val tables: List<Table>,
    /** `classpath:db/<schema>`: plain SQL, `V<n>__<what>.sql`, numbered by the module on its own. */
    val migrationsLocation: String,
) {
    init {
        require(SCHEMA_NAME.matches(schema)) { "'$schema' is not a valid schema name (lowercase letters, digits, _)" }
        tables.forEach { table ->
            require(table.tableName.startsWith("$schema.")) {
                "Table '${table.tableName}' must be named '$schema.<table>': a module owns only its own schema"
            }
        }
    }

    companion object {
        /** Schema, role and table names that may be put into SQL as identifiers; nothing else ever is. */
        val SCHEMA_NAME = Regex("[a-z][a-z0-9_]{0,62}")
    }
}

/**
 * A module's way into PostgreSQL, scoped to its schema and role: every database call runs inside [tx].
 *
 * A transaction runs as the module's role (`SET LOCAL ROLE <schema>`), so a query against another module's table fails
 * in the database. It holds database work only: no Redis call, HTTP call or `XADD` inside, because each would hold a
 * pooled connection for the whole wait. Publish events right after the commit.
 */
interface Database {
    /** The module's schema and role. */
    val schema: String

    /**
     * Runs [block] in one transaction, committed when it returns and rolled back when it throws. A call made while a
     * transaction of this module is already open (a repository inside the use case's `Transactor.run`) joins it.
     *
     * Requests and socket frames use the app pool; scheduled jobs, the outbox relay and event handlers use the jobs
     * pool. Everything is non-blocking (R2DBC): a call waiting for a connection suspends, and when none comes free
     * within the pool's acquire timeout this throws a `DomainException` carrying `SharedError.ServiceUnavailable` (503)
     * instead of waiting longer. Results are `Flow`s: consume them (`toList()`, `singleOrNull()`) inside [block].
     * [operation] names the work in the per-transaction log line (`accounts.find`); it never carries a value.
     */
    suspend fun <T> tx(operation: String = "tx", block: suspend R2dbcTransaction.() -> T): T

    /**
     * Deletes the rows of `<schema>.<table>` whose `expires_at` has passed, in chunks of [chunk] rows, each chunk its
     * own short transaction on the jobs pool. Returns how many rows went. For the module's `<module>.purge-expired`
     * job; reads already hide those rows with `expires_at > now()`.
     */
    suspend fun purgeExpired(table: String, chunk: Int = 5_000): Int

    /**
     * Keeps `<schema>.<table>`, partitioned by day on a `timestamptz` or UUIDv7 column, one day ahead: creates
     * today's and tomorrow's partitions (bounds at midnight UTC) and drops each partition that ended more than
     * [retention] ago. For the module's `<module>.purge-expired` job, on the jobs pool.
     */
    suspend fun maintainDailyPartitions(table: String, retention: Duration): PartitionChanges
}

/** What a [Database.maintainDailyPartitions] run did, by partition name (`outbox_p20261027`). */
data class PartitionChanges(val created: List<String>, val dropped: List<String>) {
    val count: Int get() = created.size + dropped.size
}

/** Hands each module its own [Database]. [schema] is the one the module declares in its [DatabaseModuleSpec]. */
interface Databases {
    fun scope(schema: String): Database
}
