package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Secrets
import com.circaso.infrastructure.impl.database.KeyspaceReplication.Strategy
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import io.kandra.core.KandraAuth
import io.kandra.core.KandraCredentials
import io.kandra.ktor.ReplicationStrategy
import io.kandra.ktor.SchemaMode
import java.io.FileInputStream
import java.net.InetSocketAddress
import java.security.KeyStore
import java.time.Duration
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory

/**
 * How to reach ScyllaDB, read once from [Config] and [Secrets], shared by the Kandra install (`serve`) and the
 * migration runner (`migrate`) so both connect the same way.
 *
 * Local: a plain node, no TLS, no credentials. ScyllaDB Cloud: TLS on (the JVM trust store, or a trust store file
 * from config), credentials from secrets, the datacenter named in config, and the app's address allowed in the
 * Cloud console.
 */
class ScyllaSettings(config: Config, private val secrets: Secrets) {
    private val scylla = config.section("scylla")

    val local: Boolean = config.environment.isLocal
    val contactPoints: String = scylla.string("contact-points", "localhost:9042")
    val datacenter: String = scylla.string("datacenter", "datacenter1")
    val tls: Boolean = scylla.boolean("tls", !local)
    private val trustStorePath: String? = scylla.string("truststore-path")

    /**
     * Whether a start creates the keyspaces and tables itself. That is a convenience for a node on this machine. Against a
     * remote cluster (ScyllaDB Cloud, even from a laptop) it ran dozens of DDL statements over the internet on every start,
     * about 25 seconds; there the schema is migrated by `./gradlew :app:migrate` and a start only validates it. The default
     * is "local environment and a loopback address"; `scylla.create-schema` (`SCYLLA_CREATE_SCHEMA`) overrides it.
     */
    val createsSchemaOnStart: Boolean = scylla.boolean("create-schema", local && contactPoints.isLoopbackOnly())

    /**
     * What Kandra does with the schema on start: create it (a node on this machine), check it against the entities (staging
     * and production, where a missing column should stop the deploy), or leave it alone (a local run against a remote cluster:
     * the check is one round trip per table, about ten seconds from a laptop, and `:app:migrate` already owns the schema).
     */
    val schemaMode: SchemaMode =
        when {
            createsSchemaOnStart -> SchemaMode.AUTO_CREATE
            local -> SchemaMode.NONE
            else -> SchemaMode.VALIDATE
        }

    /**
     * Whether a start creates the keyspaces: only where it creates the tables. Elsewhere a start never does; the
     * deploy's `migrate` step creates any keyspace that is missing, with [keyspaceReplication].
     */
    val autoCreateKeyspaces: Boolean = createsSchemaOnStart

    /**
     * The replication `migrate` gives a keyspace it creates. SimpleStrategy by default locally (the single node it has
     * always used), NetworkTopologyStrategy in [datacenter] elsewhere; factor 1 and tablets off unless configured.
     */
    val keyspaceReplication: KeyspaceReplication =
        KeyspaceReplication(
            strategy =
                scylla
                    .string("keyspace-replication-class")
                    ?.let { Strategy.parse(it) }
                    ?: if (local) Strategy.SimpleStrategy else Strategy.NetworkTopologyStrategy,
            factor = scylla.int("keyspace-replication-factor", 1),
            datacenter = datacenter,
            tablets = scylla.boolean("keyspace-tablets", false),
        )

    /** ScyllaDB Cloud uses tablets, which reject SimpleStrategy; the local node is told not to use them. */
    val localReplication: ReplicationStrategy =
        ReplicationStrategy.SimpleStrategy(
            replicationFactor = 1,
            disableTablets = true,
        )

    fun credentials(): KandraCredentials =
        KandraCredentials(secrets.get("scylla.username").orEmpty(), secrets.get("scylla.password").orEmpty())

    val authProvider = KandraAuth.custom { credentials() }

    fun trustStorePassword(): String? = secrets.get("scylla.truststore-password")

    fun addresses(): List<InetSocketAddress> =
        contactPoints.split(',').map { entry ->
            val host = entry.trim().substringBeforeLast(':', entry.trim())
            val port = entry.trim().substringAfterLast(':', "9042").toIntOrNull() ?: 9042
            InetSocketAddress(host, port)
        }

    fun sslContext(): SSLContext? {
        if (!tls) return null
        val context = SSLContext.getInstance("TLS")
        val path = trustStorePath
        if (path == null) {
            context.init(null, null, null) // the JVM's trust store
        } else {
            val store =
                KeyStore.getInstance("JKS").apply {
                    FileInputStream(path).use { load(it, trustStorePassword()?.toCharArray()) }
                }
            val factory =
                TrustManagerFactory
                    .getInstance(
                        TrustManagerFactory.getDefaultAlgorithm(),
                    ).apply { init(store) }
            context.init(null, factory.trustManagers, null)
        }
        return context
    }

    /** A plain driver session, for `migrate`. [keyspace] null opens one with no keyspace set. */
    fun openSession(keyspace: String? = null): CqlSession {
        val builder =
            CqlSession
                .builder()
                .addContactPoints(addresses())
                .withLocalDatacenter(datacenter)
                .withConfigLoader(
                    DriverConfigLoader
                        .programmaticBuilder()
                        .withDuration(DefaultDriverOption.REQUEST_TIMEOUT, Duration.ofSeconds(30)) // DDL is slow
                        .withDuration(DefaultDriverOption.CONNECTION_CONNECT_TIMEOUT, Duration.ofSeconds(10))
                        .build(),
                )
        credentials().takeIf { it.username.isNotBlank() }?.let { builder.withAuthCredentials(it.username, it.password) }
        sslContext()?.let { builder.withSslContext(it) }
        keyspace?.let { builder.withKeyspace(it) }
        return builder.build()
    }
}

private val loopbackHosts = setOf("localhost", "127.0.0.1", "::1", "[::1]")

/** True when every `host:port` entry names this machine. */
private fun String.isLoopbackOnly(): Boolean =
    split(',').map { it.trim().substringBeforeLast(':', it.trim()) }.all { it in loopbackHosts }
