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
 * Locally the keyspaces are created first; elsewhere they are provisioned ahead of time (NetworkTopologyStrategy,
 * because ScyllaDB Cloud's tablets reject SimpleStrategy).
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
            if (settings.autoCreateKeyspaces) createLocalKeyspace(session, spec.keyspace)
            val runner = KandraMigrationRunner(session, keyspace = spec.keyspace)
            val before = runner.history().size
            runner.run(*spec.migrations.toTypedArray())
            val now = runner.history().size
            applied += now - before
            log.info("migrated", kv("keyspace", spec.keyspace), kv("applied", now - before), kv("total", now))
        }
        return applied
    }

    private fun createLocalKeyspace(session: CqlSession, keyspace: String) {
        require(Regex("[a-z][a-z0-9_]*").matches(keyspace)) { "'$keyspace' is not a valid keyspace name" }
        session.execute(
            "CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = {'class': 'SimpleStrategy', 'replication_factor': 1} " +
                "AND tablets = {'enabled': false}",
        )
    }
}
