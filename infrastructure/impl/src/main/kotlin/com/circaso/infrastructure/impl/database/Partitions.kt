package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.DatabaseModuleSpec
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The SQL behind expiring rows (architecture.md → Rows that expire): chunked purges and daily range partitions. The DSL
 * can't express partition DDL, so this is the one place it is written by hand, shared by the app (over R2DBC, in
 * `ModuleDatabase`) and the `Migrate` step (over JDBC). Identifiers are checked ([quoted]) before they go in; partition
 * bounds are computed here from a date, never taken from a caller; the catalog lookups bind their values.
 */
internal object Partitions {
    private val day = DateTimeFormatter.BASIC_ISO_DATE // 20261027

    /** How a table is partitioned: by a `timestamptz` column or by a UUIDv7 id. */
    enum class Key { TIMESTAMP, UUID7 }

    /** Deletes up to [chunk] expired rows of `<schema>.<table>` and answers how many went (one row, one column). */
    fun purgeChunk(
        schema: String,
        table: String,
        chunk: Int,
    ): String {
        val name = qualified(schema, table)
        return "WITH gone AS (DELETE FROM $name WHERE ctid IN " +
            "(SELECT ctid FROM $name WHERE expires_at < now() LIMIT $chunk) RETURNING 1) SELECT count(*) FROM gone"
    }

    /** Every range-partitioned table of the schema bound to `?`: its name, and the type of its one partition column. */
    const val PARTITIONED_TABLES =
        "SELECT c.relname::text, format_type(a.atttypid, a.atttypmod) FROM pg_partitioned_table p " +
            "JOIN pg_class c ON c.oid = p.partrelid " +
            "JOIN pg_namespace n ON n.oid = c.relnamespace " +
            "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = p.partattrs[0] " +
            "WHERE n.nspname = ? AND p.partstrat = 'r' AND p.partnatts = 1"

    /** The partitions of a table, bound as schema then table. */
    const val PARTITIONS_OF =
        "SELECT c.relname::text FROM pg_inherits i " +
            "JOIN pg_class c ON c.oid = i.inhrelid " +
            "JOIN pg_class p ON p.oid = i.inhparent " +
            "JOIN pg_namespace n ON n.oid = p.relnamespace " +
            "WHERE n.nspname = ? AND p.relname = ?"

    /** A `DROP` that waits on a busy table would queue every later query behind it; the next run tries again. */
    const val SHORT_LOCK_WAIT = "SET LOCAL lock_timeout = '2s'"

    fun keyType(type: String): Key? =
        when (type) {
            "timestamp with time zone" -> Key.TIMESTAMP
            "uuid" -> Key.UUID7
            else -> null
        }

    /** Today's and tomorrow's partition names that are not in [existing], with their dates. */
    fun missingAhead(
        table: String,
        existing: Collection<String>,
        now: Instant,
    ): List<Pair<LocalDate, String>> {
        val today = LocalDate.ofInstant(now, ZoneOffset.UTC)
        return listOf(today, today.plusDays(1))
            .map { it to partitionName(table, it) }
            .filter { (_, name) -> name !in existing }
    }

    /** The partition of `<schema>.<table>` for [date], bounded at midnight UTC on both sides. */
    fun createPartition(
        schema: String,
        table: String,
        key: Key,
        date: LocalDate,
    ): String =
        "CREATE TABLE IF NOT EXISTS ${qualified(schema, partitionName(table, date))} " +
            "PARTITION OF ${qualified(schema, table)} " +
            "FOR VALUES FROM (${bound(key, date)}) TO (${bound(key, date.plusDays(1))})"

    /** The partitions in [existing] that ended at or before [cutoff]. */
    fun expired(
        table: String,
        existing: Collection<String>,
        cutoff: Instant,
    ): List<String> = existing.filter { name -> dayOf(table, name)?.let { end(it) <= cutoff } == true }

    fun dropPartition(schema: String, partition: String): String = "DROP TABLE ${qualified(schema, partition)}"

    /** `<table>_p20261027`. */
    fun partitionName(table: String, date: LocalDate): String {
        require(table.length <= 53) { "'$table' is too long to name its partitions" }
        return "${table}_p${day.format(date)}"
    }

    private fun dayOf(table: String, partition: String): LocalDate? =
        partition
            .takeIf { it.startsWith("${table}_p") }
            ?.removePrefix("${table}_p")
            ?.takeIf { it.length == 8 && it.all(Char::isDigit) }
            ?.let { LocalDate.parse(it, day) }

    private fun end(date: LocalDate): Instant = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)

    /** Midnight UTC of [date]: as a `timestamptz`, or as the lowest UUIDv7 made in that millisecond. */
    private fun bound(key: Key, date: LocalDate): String {
        val midnight = date.atStartOfDay().toInstant(ZoneOffset.UTC)
        return when (key) {
            Key.TIMESTAMP -> "'$midnight'"
            Key.UUID7 -> "'${lowestUuid7(midnight)}'"
        }
    }

    /**
     * The smallest UUIDv7 whose timestamp is [at]: the 48-bit Unix milliseconds, version 7, variant `10`, and every
     * random bit zero. Every id made at or after [at] sorts at or above it, as PostgreSQL compares uuids byte by byte.
     */
    fun lowestUuid7(at: Instant): String {
        val millis = at.toEpochMilli().toString(16).padStart(12, '0')
        return "${millis.take(8)}-${millis.drop(8)}-7000-8000-000000000000"
    }
}

/** A SQL identifier, after checking it is one of ours: lowercase letters, digits and `_`. */
internal fun quoted(identifier: String): String {
    require(DatabaseModuleSpec.SCHEMA_NAME.matches(identifier)) { "'$identifier' is not a valid identifier" }
    return "\"$identifier\""
}

internal fun qualified(schema: String, table: String): String = "${quoted(schema)}.${quoted(table)}"
