package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.api.DatabaseException
import com.circaso.infrastructure.api.DatabaseModuleSpec
import com.circaso.infrastructure.api.Databases
import com.circaso.infrastructure.api.PartitionChanges
import com.circaso.infrastructure.impl.logging.kv
import com.circaso.infrastructure.impl.logging.millisSince
import com.circaso.shared.EventEnvelope
import io.r2dbc.postgresql.api.PostgresqlException
import io.r2dbc.spi.R2dbcException
import io.r2dbc.spi.Row
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import org.jetbrains.exposed.v1.core.TextColumnType
import org.jetbrains.exposed.v1.core.statements.StatementType
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.database.Transactions")

/** The transaction a coroutine is inside, so a repository called within a use case's transaction joins it. */
private class OpenTransaction(val schema: String, val transaction: R2dbcTransaction) :
    AbstractCoroutineContextElement(Key) {
    object Key : CoroutineContext.Key<OpenTransaction>
}

/**
 * The runtime half of "a transaction holds database work only" (Konsist is the other): Redis, outbound HTTP and
 * `EventBus.publish` call this first, and it throws when the calling coroutine is inside a `Database.tx`, where the
 * wait would hold a pooled connection.
 */
internal suspend fun requireOutsideTransaction(what: String) {
    val open = currentCoroutineContext()[OpenTransaction.Key] ?: return
    throw IllegalStateException(
        "$what called inside a '${open.schema}' transaction; a transaction holds database work only, so call it " +
            "after the commit",
    )
}

/** Hands each module a [Database] on the shared pools, scoped to its schema and role. */
class PostgresDatabases(private val pools: PostgresPools, private val clock: Clock = Clock.systemUTC()) : Databases {
    override fun scope(schema: String): Database = ModuleDatabase(schema, pools, clock)
}

/**
 * [Database] for one module, over R2DBC: nothing blocks a thread. Every transaction runs on the pool the work belongs
 * to ([PostgresPools.forWork]), starts with `SET LOCAL ROLE <schema>` (which ends with the transaction, so a connection
 * goes back to the pool clean) and writes one log line: DEBUG normally, WARN when slower than the pool's threshold
 * (decision 2026-10-27, (8)).
 */
class ModuleDatabase(
    override val schema: String,
    private val pools: PostgresPools,
    private val clock: Clock = Clock.systemUTC(),
) : Database {
    init {
        require(DatabaseModuleSpec.SCHEMA_NAME.matches(schema)) { "'$schema' is not a valid schema name" }
    }

    private val outboxTable by lazy { OutboxTable(schema) }

    override suspend fun <T> tx(operation: String, block: suspend R2dbcTransaction.() -> T): T {
        val context = currentCoroutineContext()
        context[OpenTransaction.Key]?.let { open ->
            check(open.schema == schema) {
                "A '$schema' transaction can't open inside a '${open.schema}' one: no transaction spans two modules"
            }
            return withoutValues(operation) { open.transaction.block() }
        }
        return run(pools.forWork(context), operation, block)
    }

    override suspend fun writeOutbox(envelope: EventEnvelope) {
        val open =
            checkNotNull(currentCoroutineContext()[OpenTransaction.Key]) {
                "writeOutbox belongs inside the transaction that makes the change"
            }
        check(open.schema == schema) { "'$schema' can't write its outbox inside a '${open.schema}' transaction" }
        withoutValues("outbox.write") { outboxTable.write(envelope) }
    }

    private suspend fun <T> run(
        pool: Pool,
        operation: String,
        block: suspend R2dbcTransaction.() -> T,
    ): T {
        val started = System.nanoTime()
        var transaction: R2dbcTransaction? = null
        var outcome = "rollback"
        try {
            val result =
                suspendTransaction(db = pool.exposed) {
                    transaction = this
                    exec("SET LOCAL ROLE ${quoted(schema)}")
                    withContext(OpenTransaction(schema, this)) { block() }
                }
            outcome = "commit"
            return result
        } catch (e: Exception) {
            if (e.isAcquireTimeout()) throw pool.unavailable(e)
            throw e.withoutValues(operation)
        } finally {
            // The role switch is ours, not the caller's: it isn't counted.
            val statements = ((transaction?.statementCount ?: 1) - 1).coerceAtLeast(0)
            logLine(pool, operation, statements, 0L.millisSince(started), outcome)
        }
    }

    private fun logLine(
        pool: Pool,
        operation: String,
        statements: Int,
        durationMs: Long,
        outcome: String,
    ) {
        val slow = durationMs > pool.settings.slowAfter.toMillis()
        if (!slow && !log.isDebugEnabled) return
        val fields =
            arrayOf(
                kv("kind", "db.tx"),
                kv("operation", operation),
                kv("schema", schema),
                kv("pool", pool.name),
                kv("statements", statements),
                kv("durationMs", durationMs),
                kv("outcome", outcome),
            )
        if (slow) log.warn("slow transaction", *fields) else log.debug("transaction", *fields)
    }

    override suspend fun purgeExpired(table: String, chunk: Int): Int {
        require(chunk > 0) { "chunk must be positive (was $chunk)" }
        val sql = Partitions.purgeChunk(schema, table, chunk)
        var total = 0
        withContext(OnJobsPool) {
            while (true) {
                val deleted = tx("purge.$table") { query(sql) { (it.get(0) as Number).toInt() }.single() }
                total += deleted
                if (deleted < chunk) break
            }
        }
        return total
    }

    override suspend fun maintainDailyPartitions(table: String, retention: Duration): PartitionChanges =
        withContext(OnJobsPool) {
            tx("partitions.$table") {
                exec(Partitions.SHORT_LOCK_WAIT)
                val key =
                    query(Partitions.PARTITIONED_TABLES, schema) { it.text(0) to it.text(1) }
                        .firstOrNull { it.first == table }
                        ?.let { Partitions.keyType(it.second) }
                        ?: error("$schema.$table is not partitioned by range on one timestamptz or uuid column")
                val existing = query(Partitions.PARTITIONS_OF, schema, table) { it.text(0) }
                val now = clock.instant()
                val created =
                    Partitions.missingAhead(table, existing, now).map { (date, name) ->
                        exec(Partitions.createPartition(schema, table, key, date))
                        name
                    }
                val dropped =
                    Partitions.expired(table, existing, now.minus(retention)).onEach { name ->
                        exec(Partitions.dropPartition(schema, name))
                    }
                if (created.isNotEmpty() || dropped.isNotEmpty()) {
                    log.info(
                        "partitions",
                        kv("kind", "db.partitions"),
                        kv("schema", schema),
                        kv("table", table),
                        kv("created", created),
                        kv("dropped", dropped),
                    )
                }
                PartitionChanges(created, dropped)
            }
        }
}

/** Runs [block], turning a failed statement into a [DatabaseException] that carries no bound value. */
private suspend fun <T> withoutValues(operation: String, block: suspend () -> T): T =
    try {
        block()
    } catch (e: Exception) {
        throw e.withoutValues(operation)
    }

/**
 * Exposed's exception message quotes the statement (with its values when a transaction debugs), and the server's text
 * can quote a value too (`invalid input syntax for type uuid: "…"`). What leaves `Database.tx` keeps only the
 * operation, the SQLSTATE and the constraint's name, and no cause; anything that isn't a database error passes as is.
 */
private fun Exception.withoutValues(operation: String): Exception {
    if (this is DatabaseException) return this
    val failures = generateSequence<Throwable>(this) { it.cause }.take(8).filterIsInstance<R2dbcException>().toList()
    val failure = failures.lastOrNull { !it.sqlState.isNullOrEmpty() } ?: failures.lastOrNull() ?: return this
    val constraint = (failure as? PostgresqlException)?.errorDetails?.constraintName?.orElse(null)
    return DatabaseException(operation, failure.sqlState.orEmpty().ifEmpty { "unknown" }, constraint)
}

/** Runs a hand-written query with text values bound in order, and reads every row inside the transaction. */
internal suspend fun <T : Any> R2dbcTransaction.query(
    sql: String,
    vararg values: String,
    read: (Row) -> T?,
): List<T> =
    exec(sql, values.map { TextColumnType() to it }, StatementType.SELECT, read)
        ?.toList()
        .orEmpty()
        .filterNotNull()

internal fun Row.text(index: Int): String = get(index, String::class.java)!!
