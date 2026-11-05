package com.circaso.infrastructure.impl.database

import io.r2dbc.spi.Connection
import io.r2dbc.spi.ConnectionFactory
import io.r2dbc.spi.ConnectionFactoryMetadata
import io.r2dbc.spi.IsolationLevel
import io.r2dbc.spi.Option
import io.r2dbc.spi.Result
import io.r2dbc.spi.TransactionDefinition
import io.r2dbc.spi.Wrapped
import org.reactivestreams.Publisher
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * How one module's transaction starts: the isolation Exposed asks for, the module's [role], and whether the commit
 * waits for the WAL flush ([durable]). [ModuleDatabase] hands it to Exposed's connection before the first statement,
 * and [OneTripBegin] turns it into a single round trip ([beginSql]).
 *
 * It also carries back how long the transaction waited for a pooled connection ([waitNanos], set when the connection
 * is borrowed), for the `db.tx` log line.
 */
internal class ModuleTransaction(
    val role: String,
    val durable: Boolean,
    private val isolationLevel: IsolationLevel?,
    private val readOnly: Boolean?,
) : TransactionDefinition {
    init {
        quoted(role) // a schema name, checked, or this throws before anything reaches the server
    }

    /** Nanoseconds spent waiting for the pool's connection; -1 until one is borrowed. */
    @Volatile
    var waitNanos: Long = -1

    @Suppress("UNCHECKED_CAST")
    override fun <T : Any?> getAttribute(option: Option<T>): T? =
        when (option) {
            TransactionDefinition.ISOLATION_LEVEL -> isolationLevel as T?
            TransactionDefinition.READ_ONLY -> readOnly as T?
            else -> null
        }

    /**
     * `BEGIN …; SET LOCAL ROLE "<role>"` (and `SET LOCAL synchronous_commit = off` when not [durable]): one simple
     * query, so one round trip. PostgreSQL runs the statements after a `BEGIN` inside that transaction block, and an
     * error in any of them leaves the block failed, so nothing runs under the login role by mistake. Both `SET LOCAL`s
     * end with the transaction.
     */
    fun beginSql(): String =
        buildString {
            append("BEGIN")
            val modes =
                listOfNotNull(
                    isolationLevel?.let {
                        "ISOLATION LEVEL ${it.asSql()}"
                    },
                    readOnly?.let { if (it) "READ ONLY" else "READ WRITE" },
                )
            if (modes.isNotEmpty()) append(' ').append(modes.joinToString(", "))
            append("; SET LOCAL ROLE ").append(quoted(role))
            if (!durable) append("; SET LOCAL synchronous_commit = off")
        }
}

/**
 * The driver's [ConnectionFactory], with connections that start a [ModuleTransaction] in one round trip. It sits under
 * r2dbc-pool, so the pool still sees `beginTransaction` and still rolls back a connection given back mid-transaction.
 */
internal class OneTripBegin(private val driver: ConnectionFactory) : ConnectionFactory {
    override fun create(): Publisher<out Connection> = Mono.from(driver.create()).map { OneTripBeginConnection(it) }

    override fun getMetadata(): ConnectionFactoryMetadata = driver.metadata
}

internal class OneTripBeginConnection(private val connection: Connection) :
    Connection by connection,
    Wrapped<Connection> {
    override fun beginTransaction(definition: TransactionDefinition): Publisher<Void> {
        if (definition !is ModuleTransaction) return connection.beginTransaction(definition)
        return Flux
            .defer { Flux.from(connection.createStatement(definition.beginSql()).execute()) }
            // Every statement's result is read, so an error in the second or third surfaces here.
            .concatMap(Result::getRowsUpdated)
            .then()
            .onErrorResume { failure ->
                // A failed SET leaves the block open and failed: close it before the error goes up, so the connection
                // goes back to the pool clean (the pool's own rollback only knows the begin, not its outcome). A plain
                // ROLLBACK, not rollbackTransaction(): the driver raises the error before the server's ReadyForQuery
                // says the block is open, so it would still think there is nothing to roll back. A ROLLBACK with no
                // block open is only a warning.
                Flux
                    .from(connection.createStatement("ROLLBACK").execute())
                    .concatMap(Result::getRowsUpdated)
                    .then()
                    // Can't even roll back: close the socket, so the pool's release check drops this connection.
                    .onErrorResume { Mono.from(connection.close()).onErrorResume { Mono.empty() } }
                    .then(Mono.error(failure))
            }
    }

    override fun unwrap(): Connection = connection
}
