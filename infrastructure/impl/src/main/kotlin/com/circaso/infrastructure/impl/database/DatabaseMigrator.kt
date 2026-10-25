package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.KandraModuleSpec
import com.circaso.infrastructure.impl.logging.kv
import com.datastax.oss.driver.api.core.CqlSession
import io.kandra.migrate.KandraMigrationRunner
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("com.circaso.infrastructure.impl.database.Migrator")

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
    /** Returns how many migrations were applied in all. */
    fun migrate(specs: List<KandraModuleSpec>): Int {
        if (specs.isEmpty()) return 0 // nothing to migrate, so nothing to connect to
        return settings.openSession().use { session -> migrate(session, specs) }
    }

    internal fun migrate(session: CqlSession, specs: List<KandraModuleSpec>): Int {
        var applied = 0
        for (spec in specs) {
            ensureKeyspace(session, spec.keyspace)
            val runner = KandraMigrationRunner(session, keyspace = spec.keyspace)
            val before = runner.history().size
            runner.run(*spec.migrations.toTypedArray())
            val now = runner.history().size
            applied += now - before
            log.info("migrated", kv("keyspace", spec.keyspace), kv("applied", now - before), kv("total", now))
        }
        return applied
    }

    private fun ensureKeyspace(session: CqlSession, keyspace: String) {
        if (keyspaceExists(session, keyspace)) {
            log.info("keyspace already exists", kv("keyspace", keyspace))
            return
        }
        session.execute(settings.keyspaceReplication.createKeyspaceCql(keyspace))
        log.info(
            "keyspace created",
            kv("keyspace", keyspace),
            kv("strategy", settings.keyspaceReplication.strategy.name),
            kv("factor", settings.keyspaceReplication.factor),
        )
    }

    private fun keyspaceExists(session: CqlSession, keyspace: String): Boolean =
        session
            .execute("SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = ?", keyspace)
            .one() != null
}
