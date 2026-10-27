package com.circaso.infrastructure.impl

import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.impl.database.PostgresSettings
import com.circaso.infrastructure.impl.redis.RedisConnection
import com.circaso.infrastructure.testing.TestPostgres
import org.testcontainers.containers.GenericContainer
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

/** Settings pointed at the shared test container, for infrastructure's own tests of the pools and Migrate. */
fun TestPostgres.settings(extra: Map<String, String> = emptyMap()): PostgresSettings =
    env(extra).let { PostgresSettings(EnvConfig(it), EnvSecrets(it)) }
