package com.circaso.users.data.entities

import com.kandraorm.kandra.annotations.PartitionKey
import com.kandraorm.kandra.annotations.ScyllaTable
import java.util.UUID

@ScyllaTable(name = "profiles", keyspace = "circaso_users")
data class ProfileRow(@PartitionKey val userId: UUID, val displayName: String)
