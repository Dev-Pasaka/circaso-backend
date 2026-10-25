package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.impl.config.EnvConfig
import com.circaso.infrastructure.impl.config.EnvSecrets
import com.circaso.infrastructure.impl.database.KeyspaceReplication.Strategy
import io.kandra.ktor.SchemaMode
import kotlin.test.Test
import kotlin.test.assertEquals

class ScyllaSettingsTest {
    private val cloud = "node-0.example.cloud:9042,node-1.example.cloud:9042"

    private fun settings(vararg env: Pair<String, String>) =
        ScyllaSettings(EnvConfig(mapOf(*env)), EnvSecrets(emptyMap()))

    @Test
    fun `a node on this machine creates its schema on start`() {
        for (points in listOf("localhost:9042", "127.0.0.1:9042", "[::1]:9042", "localhost:9042, 127.0.0.1:9043")) {
            val s = settings("CIRCASO_ENV" to "local", "SCYLLA_CONTACT_POINTS" to points)
            assertEquals(true, s.createsSchemaOnStart, points)
            assertEquals(true, s.autoCreateKeyspaces, points)
            assertEquals(SchemaMode.AUTO_CREATE, s.schemaMode, points)
        }
    }

    @Test
    fun `a remote cluster is only validated, even from a local environment`() {
        val s = settings("CIRCASO_ENV" to "local", "SCYLLA_CONTACT_POINTS" to cloud)
        assertEquals(false, s.createsSchemaOnStart)
        assertEquals(false, s.autoCreateKeyspaces)
        assertEquals(SchemaMode.NONE, s.schemaMode)
    }

    @Test
    fun `one remote node among local ones is still a remote cluster`() {
        val s =
            settings("CIRCASO_ENV" to "local", "SCYLLA_CONTACT_POINTS" to "localhost:9042,node-0.example.cloud:9042")
        assertEquals(false, s.createsSchemaOnStart)
    }

    @Test
    fun `staging and production never create the schema on start`() {
        for (environment in listOf("staging", "production")) {
            val s = settings("CIRCASO_ENV" to environment, "SCYLLA_CONTACT_POINTS" to "localhost:9042")
            assertEquals(false, s.createsSchemaOnStart, environment)
            assertEquals(SchemaMode.VALIDATE, s.schemaMode, environment)
        }
    }

    @Test
    fun `scylla create-schema overrides the default either way`() {
        assertEquals(
            true,
            settings("CIRCASO_ENV" to "local", "SCYLLA_CONTACT_POINTS" to cloud, "SCYLLA_CREATE_SCHEMA" to "true")
                .createsSchemaOnStart,
        )
        assertEquals(
            false,
            settings(
                "CIRCASO_ENV" to "local",
                "SCYLLA_CONTACT_POINTS" to "localhost:9042",
                "SCYLLA_CREATE_SCHEMA" to "false",
            ).createsSchemaOnStart,
        )
    }

    @Test
    fun `migrate's keyspaces are SimpleStrategy only on a node on this machine`() {
        val onThisMachine = settings("CIRCASO_ENV" to "local", "SCYLLA_CONTACT_POINTS" to "localhost:9042")
        val laptopToCloud =
            settings("CIRCASO_ENV" to "local", "SCYLLA_CONTACT_POINTS" to cloud, "SCYLLA_DATACENTER" to "AWS_EU_WEST_1")
        val staging = settings("CIRCASO_ENV" to "staging", "SCYLLA_DATACENTER" to "AWS_EU_WEST_1")

        assertEquals(
            KeyspaceReplication(Strategy.SimpleStrategy, 1, "datacenter1", tablets = false),
            onThisMachine.keyspaceReplication,
        )
        for (remote in listOf(laptopToCloud, staging)) {
            assertEquals(
                KeyspaceReplication(Strategy.NetworkTopologyStrategy, 1, "AWS_EU_WEST_1", tablets = false),
                remote.keyspaceReplication,
            )
        }
    }

    @Test
    fun `the keyspace replication is read from its settings`() {
        val replication =
            settings(
                "CIRCASO_ENV" to "production",
                "SCYLLA_DATACENTER" to "dc-east",
                "SCYLLA_KEYSPACE_REPLICATION_CLASS" to "SimpleStrategy",
                "SCYLLA_KEYSPACE_REPLICATION_FACTOR" to "3",
                "SCYLLA_KEYSPACE_TABLETS" to "true",
            ).keyspaceReplication

        assertEquals(KeyspaceReplication(Strategy.SimpleStrategy, 3, "dc-east", tablets = true), replication)
    }
}
