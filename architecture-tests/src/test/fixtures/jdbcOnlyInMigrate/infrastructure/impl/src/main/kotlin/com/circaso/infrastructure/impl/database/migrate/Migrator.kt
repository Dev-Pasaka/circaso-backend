package com.circaso.infrastructure.impl.database.migrate

import org.flywaydb.core.Flyway
import java.sql.DriverManager

// Allowed: the Migrate step is the one place JDBC is used.
class Migrator(private val flyway: Flyway) {
    fun connect() = DriverManager.getConnection("jdbc:postgresql://localhost/circaso")
}
