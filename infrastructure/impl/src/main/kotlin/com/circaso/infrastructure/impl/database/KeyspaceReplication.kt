package com.circaso.infrastructure.impl.database

/**
 * How `migrate` creates a module's keyspace when it is missing. SimpleStrategy suits a single local node;
 * NetworkTopologyStrategy names the datacenter, which a cluster such as ScyllaDB Cloud needs. Tablets stay off by
 * default, like the keyspaces already on the sandbox cluster.
 */
data class KeyspaceReplication(
    val strategy: Strategy,
    val factor: Int,
    val datacenter: String,
    val tablets: Boolean,
) {
    init {
        require(factor >= 1) { "The keyspace replication factor must be at least 1 (was $factor)" }
    }

    enum class Strategy {
        SimpleStrategy,
        NetworkTopologyStrategy,
        ;

        companion object {
            fun parse(raw: String): Strategy =
                entries.firstOrNull { it.name.equals(raw.trim(), ignoreCase = true) }
                    ?: throw IllegalStateException(
                        "SCYLLA_KEYSPACE_REPLICATION_CLASS must be SimpleStrategy or NetworkTopologyStrategy " +
                            "(was '$raw')",
                    )
        }
    }

    /** The statement that creates [keyspace] if it doesn't exist. It never changes a keyspace that does. */
    fun createKeyspaceCql(keyspace: String): String {
        require(keyspaceName.matches(keyspace)) { "'$keyspace' is not a valid keyspace name" }
        val replication =
            when (strategy) {
                Strategy.SimpleStrategy -> "{'class': 'SimpleStrategy', 'replication_factor': $factor}"
                Strategy.NetworkTopologyStrategy -> "{'class': 'NetworkTopologyStrategy', '$datacenter': $factor}"
            }
        return "CREATE KEYSPACE IF NOT EXISTS $keyspace WITH replication = $replication " +
            "AND tablets = {'enabled': $tablets}"
    }

    private companion object {
        val keyspaceName = Regex("[a-z][a-z0-9_]*")
    }
}
