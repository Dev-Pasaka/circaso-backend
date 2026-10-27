package com.circaso.infrastructure.api

import com.circaso.shared.EventEnvelope
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
    /**
     * Whether the module publishes events. Its V1 script then creates `<schema>.outbox` in the shared shape
     * (architecture.md → Durable events), it writes rows with [Database.writeOutbox], and infrastructure gives the
     * relay an `OutboxStore` over that table: the module writes no outbox code of its own.
     */
    val outbox: Boolean = false,
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
 * in the database. It holds database work only: a Redis call, an outbound HTTP call or `EventBus.publish` made inside
 * one throws, because each would hold a pooled connection for the whole wait. Publish events right after the commit.
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
     *
     * [operation] names the work in the per-transaction log line (`accounts.find`): a string literal, never a value
     * (Konsist checks it). A failed statement surfaces as a [DatabaseException], which never carries a bound value.
     */
    suspend fun <T> tx(operation: String, block: suspend R2dbcTransaction.() -> T): T

    /**
     * Writes [envelope] to the module's `<schema>.outbox` in the transaction that is open (it must be called inside
     * [tx], so the event lands with the change that caused it or not at all). Needs `outbox = true` in the spec.
     */
    suspend fun writeOutbox(envelope: EventEnvelope)

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

/**
 * A statement that failed in PostgreSQL. It names the [operation], the [sqlState] and, for a constraint, the
 * [constraint]; it never carries the statement's bound values or the server's detail (which can quote them), and
 * keeps no cause, so a log line or a 500 can't leak a phone number or a key.
 */
class DatabaseException(val operation: String, val sqlState: String, val constraint: String?) :
    RuntimeException(describe(operation, sqlState, constraint)) {
    /** `23505`: a unique index refused the row; [constraint] says which claim was lost. */
    val isUniqueViolation: Boolean get() = sqlState == "23505"

    /** `23514`: a `CHECK` refused the row. */
    val isCheckViolation: Boolean get() = sqlState == "23514"

    private companion object {
        fun describe(
            operation: String,
            sqlState: String,
            constraint: String?,
        ): String = "Database operation '$operation' failed: SQLSTATE $sqlState" + (constraint?.let { " ($it)" } ?: "")
    }
}

/** What a [Database.maintainDailyPartitions] run did, by partition name (`outbox_p20261027`). */
data class PartitionChanges(val created: List<String>, val dropped: List<String>) {
    val count: Int get() = created.size + dropped.size
}

/** Hands each module its own [Database]. [schema] is the one the module declares in its [DatabaseModuleSpec]. */
interface Databases {
    fun scope(schema: String): Database
}
