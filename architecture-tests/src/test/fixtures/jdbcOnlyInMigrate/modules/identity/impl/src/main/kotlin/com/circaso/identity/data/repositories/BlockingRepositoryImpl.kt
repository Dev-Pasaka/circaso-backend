package com.circaso.identity.data.repositories

import org.jetbrains.exposed.v1.jdbc.selectAll
import java.sql.Connection

// The module talking JDBC: every call would hold a thread while the database answers.
class BlockingRepositoryImpl(private val connection: Connection)
