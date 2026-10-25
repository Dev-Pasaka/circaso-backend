package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.KandraModuleSpec
import com.circaso.infrastructure.impl.logging.kv
import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.migrate.KandraMigrationRunner
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.database.Migrator")

/** What one `migrate` run did: how many migrations it applied, and which keyspaces it had to create first. */
data class MigrationOutcome(val applied: Int, val createdKeyspaces: List<String>)

/**
 * Runs each module's migrations once per deploy, one `kandra-migrate` runner per keyspace so identity and users
 * number theirs independently, each with its own checksummed history. Only `app migrate` calls this: containers
 * never migrate at boot, so two of them can't race on one keyspace, and a failed migration stops the deploy while
 * the old containers keep serving.
 *
 * Migrations name their tables with the keyspace and use `IF NOT EXISTS`; an applied migration is never edited.
 * Each module's keyspace is created first if it is missing, in every environment, so a new module doesn't stop the
 * deploy (`KandraMigrationRunner` needs the keyspace for its lock table). The replication comes from
 * [ScyllaSettings.keyspaceReplication]; a keyspace that already exists is never altered.
 */
class DatabaseMigrator(private val settings: ScyllaSettings) {
    fun migrate(specs: List<KandraModuleSpec>): MigrationOutcome {
        if (specs.isEmpty()) return MigrationOutcome(0, emptyList()) // nothing to migrate, so nothing to connect to
        return settings.openSession().use { session -> migrate(session, specs) }
    }

    internal fun migrate(session: CqlSession, specs: List<KandraModuleSpec>): MigrationOutcome {
        var applied = 0
        val created = mutableListOf<String>()
        for (spec in specs) {
            if (ensureKeyspace(session, spec.keyspace)) created += spec.keyspace
            val runner = KandraMigrationRunner(session, keyspace = spec.keyspace)
            val before = runner.history().size
            runner.run(*spec.migrations.toTypedArray())
            val now = runner.history().size
            applied += now - before
            log.info("migrated", kv("keyspace", spec.keyspace), kv("applied", now - before), kv("total", now))
        }
        return MigrationOutcome(applied, created)
    }

    /** Creates [keyspace] if it is missing; returns whether it did. */
    private fun ensureKeyspace(session: CqlSession, keyspace: String): Boolean {
        if (keyspaceExists(session, keyspace)) {
            log.debug("keyspace already exists", kv("keyspace", keyspace))
            return false
        }
        session.execute(settings.keyspaceReplication.createKeyspaceCql(keyspace))
        log.info(
            "keyspace created",
            kv("keyspace", keyspace),
            kv("strategy", settings.keyspaceReplication.strategy.name),
            kv("factor", settings.keyspaceReplication.factor),
        )
        return true
    }

    private fun keyspaceExists(session: CqlSession, keyspace: String): Boolean =
        session
            .execute("SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = ?", keyspace)
            .one() != null
}
