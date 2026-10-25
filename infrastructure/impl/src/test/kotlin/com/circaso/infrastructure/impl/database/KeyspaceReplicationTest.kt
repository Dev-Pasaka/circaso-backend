package com.circaso.infrastructure.impl.database

import com.circaso.infrastructure.impl.database.KeyspaceReplication.Strategy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class KeyspaceReplicationTest {
    @Test
    fun `SimpleStrategy names only the factor`() {
        val replication = KeyspaceReplication(Strategy.SimpleStrategy, factor = 1, datacenter = "dc1", tablets = false)

        assertEquals(
            "CREATE KEYSPACE IF NOT EXISTS circaso_wallet WITH replication = " +
                "{'class': 'SimpleStrategy', 'replication_factor': 1} AND tablets = {'enabled': false}",
            replication.createKeyspaceCql("circaso_wallet"),
        )
    }

    @Test
    fun `NetworkTopologyStrategy puts the factor on the datacenter`() {
        val replication =
            KeyspaceReplication(
                Strategy.NetworkTopologyStrategy,
                factor = 3,
                datacenter = "AWS_EU_WEST_1",
                tablets = true,
            )

        assertEquals(
            "CREATE KEYSPACE IF NOT EXISTS circaso_wallet WITH replication = " +
                "{'class': 'NetworkTopologyStrategy', 'AWS_EU_WEST_1': 3} AND tablets = {'enabled': true}",
            replication.createKeyspaceCql("circaso_wallet"),
        )
    }

    @Test
    fun `a keyspace name that isn't a plain identifier is refused`() {
        val replication = KeyspaceReplication(Strategy.SimpleStrategy, factor = 1, datacenter = "dc1", tablets = false)

        assertFailsWith<IllegalArgumentException> { replication.createKeyspaceCql("circaso; DROP KEYSPACE x") }
    }

    @Test
    fun `a factor below one is refused`() {
        assertFailsWith<IllegalArgumentException> {
            KeyspaceReplication(Strategy.SimpleStrategy, factor = 0, datacenter = "dc1", tablets = false)
        }
    }

    @Test
    fun `the strategy is parsed ignoring case and anything else is refused`() {
        assertEquals(Strategy.NetworkTopologyStrategy, Strategy.parse("networktopologystrategy"))
        assertFailsWith<IllegalStateException> { Strategy.parse("EverywhereStrategy") }
    }
}
