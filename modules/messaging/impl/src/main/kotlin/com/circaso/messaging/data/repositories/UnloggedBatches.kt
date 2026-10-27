package com.circaso.messaging.data.repositories

import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.DefaultConsistencyLevel
import com.datastax.oss.driver.api.core.cql.BatchStatement
import com.datastax.oss.driver.api.core.cql.DefaultBatchType
import com.datastax.oss.driver.api.core.cql.PreparedStatement
import com.datastax.oss.driver.api.core.cql.Statement
import io.kandra.runtime.KandraRuntime
import io.kandra.runtime.driver.executeSuspend
import io.kandra.runtime.driver.prepareSuspend
import java.util.concurrent.ConcurrentHashMap

/**
 * Unlogged batches, which Kandra doesn't offer (its batches are logged): a fan-out writes rows of many partitions
 * that need no atomicity, so the batch log would only cost a second write each (Messaging → Data → Fan-out). Each
 * statement is written once as CQL and prepared once. Writes are `LOCAL_QUORUM`, as Kandra's are, and idempotent
 * (the same rows written again), so the driver may retry them.
 *
 * A batch ends at the row count asked for or at [maxBatchBytes], whichever comes first (decision 2026-10-27):
 * ScyllaDB refuses a batch over 1 MB, and `SENDER_KEY` copies of up to 64 KB each would pass that in a few dozen
 * rows. A single statement goes on its own, never as a batch.
 */
class UnloggedBatches(runtime: KandraRuntime, private val maxBatchBytes: Int = MAX_BATCH_BYTES) {
    private val session: CqlSession = runtime.session
    private val prepared = ConcurrentHashMap<String, PreparedStatement>()

    /** Runs [cql] once per entry of [rows], at most [batchSize] statements to a batch. */
    suspend fun run(
        cql: String,
        rows: List<List<Any>>,
        batchSize: Int,
    ) = runAll(rows.map { Write(cql, it) }, batchSize)

    /** Runs [writes] in order, at most [batchSize] statements to a batch, whatever table each one writes. */
    suspend fun runAll(writes: List<Write>, batchSize: Int) {
        for (chunk in plan(writes, batchSize, maxBatchBytes)) {
            session.executeSuspend(statementFor(chunk))
        }
    }

    private suspend fun statementFor(chunk: List<Write>): Statement<*> {
        val statements = chunk.map { prepare(it.cql).bind(*it.values.toTypedArray()) }
        statements.singleOrNull()?.let { single ->
            return single
                .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM)
                .setIdempotent(true)
        }
        return BatchStatement
            .newInstance(DefaultBatchType.UNLOGGED)
            .addAll(statements)
            .setConsistencyLevel(DefaultConsistencyLevel.LOCAL_QUORUM)
            .setIdempotent(true)
    }

    /** One statement: its CQL and the values bound to it. */
    data class Write(val cql: String, val values: List<Any>) {
        /** Roughly what it adds to a batch: its values, text at a byte a character (base64 is ASCII). */
        val estimatedBytes: Int
            get() = STATEMENT_OVERHEAD + values.sumOf { if (it is String) it.length else VALUE_BYTES }
    }

    private suspend fun prepare(cql: String): PreparedStatement =
        prepared[cql] ?: session.prepareSuspend(cql).also { prepared[cql] = it }

    companion object {
        /** Well under ScyllaDB's 1 MB batch limit. */
        const val MAX_BATCH_BYTES = 256 * 1_024
        private const val STATEMENT_OVERHEAD = 64
        private const val VALUE_BYTES = 16

        /** [writes] in order, cut into batches of at most [batchSize] statements and [maxBytes] estimated bytes. */
        fun plan(
            writes: List<Write>,
            batchSize: Int,
            maxBytes: Int,
        ): List<List<Write>> {
            val batches = mutableListOf<List<Write>>()
            var current = mutableListOf<Write>()
            var bytes = 0
            for (write in writes) {
                val full = current.size >= batchSize || bytes + write.estimatedBytes > maxBytes
                if (current.isNotEmpty() && full) {
                    batches += current
                    current = mutableListOf()
                    bytes = 0
                }
                current += write
                bytes += write.estimatedBytes
            }
            if (current.isNotEmpty()) batches += current
            return batches
        }
    }
}
