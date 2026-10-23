package com.circaso.users.data.entities

import com.kandraorm.kandra.annotations.ScyllaTable

@ScyllaTable(name = "profiles", keyspace = "circaso_users")
data class ProfileRow(val id: String)
