package com.circaso.infrastructure.impl

import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.impl.database.ScyllaSettings
import com.circaso.infrastructure.impl.redis.RedisConnection
import org.testcontainers.containers.GenericContainer
import org.testcontainers.scylladb.ScyllaDBContainer
import org.testcontainers.utility.DockerImageName

/** One Valkey container for the whole test JVM; tests keep apart by using their own keys and streams. */
object TestRedis {
    private val container: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("valkey/valkey:8-alpine"))
            .withExposedPorts(6379)
            .also { it.start() }
    }

    val url: String get() = "redis://${container.host}:${container.getMappedPort(6379)}"

    fun connect(): RedisConnection = RedisConnection(url)
}

/** One ScyllaDB container for the whole test JVM; tests keep apart by using their own keyspaces. */
object TestScylla {
    private val container: ScyllaDBContainer by lazy {
        ScyllaDBContainer(DockerImageName.parse("scylladb/scylla:2026.1")).also { it.start() }
    }

    private val contactPoint get() = container.contactPoint.let { "${it.hostString}:${it.port}" }

    /** Settings for the container, in the given environment. TLS is off: the container speaks plain CQL. */
    fun settings(environment: String = "local", extra: Map<String, String> = emptyMap()): ScyllaSettings =
        ScyllaSettings(
            EnvConfig(
                mapOf(
                    "CIRCASO_ENV" to environment,
                    "SCYLLA_CONTACT_POINTS" to contactPoint,
                    "SCYLLA_DATACENTER" to "datacenter1",
                    "SCYLLA_TLS" to "false",
                ) +
                    // the container is a throw-away node: in the local environment its tables are made on start
                    (if (environment == "local") mapOf("SCYLLA_CREATE_SCHEMA" to "true") else emptyMap()) +
                    extra,
            ),
            EnvSecrets(emptyMap()),
        )
}
