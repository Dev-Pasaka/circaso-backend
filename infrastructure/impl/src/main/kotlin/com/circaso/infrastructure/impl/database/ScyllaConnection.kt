package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Secrets
import com.datastax.oss.driver.api.core.CqlSession
import com.datastax.oss.driver.api.core.config.DefaultDriverOption
import com.datastax.oss.driver.api.core.config.DriverConfigLoader
import io.kandra.core.KandraAuth
import io.kandra.core.KandraCredentials
import io.kandra.ktor.ReplicationStrategy
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

    /** Local keyspaces are created on demand; everywhere else they are provisioned ahead of time. */
    val autoCreateKeyspaces: Boolean = local

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
