package com.circaso.identity.data.entities

import com.kandraorm.kandra.annotations.ScyllaTable

@ScyllaTable(name = "nothing")
data class NoKeyspaceRow(val id: String)

@ScyllaTable(name = "wrong", keyspace = "circaso_users")
data class WrongKeyspaceRow(val id: String)

@ScyllaTable(name = "claims", keyspace = "circaso_identity")
data class ProfileRow(val id: String)
