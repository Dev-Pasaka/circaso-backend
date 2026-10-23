package com.circaso.identity.data.entities

import com.kandraorm.kandra.annotations.PartitionKey
import com.kandraorm.kandra.annotations.ScyllaTable
import java.util.UUID

@ScyllaTable(name = "accounts_by_id", keyspace = "circaso_identity")
data class AccountRow(@PartitionKey val userId: UUID, val active: Boolean)
