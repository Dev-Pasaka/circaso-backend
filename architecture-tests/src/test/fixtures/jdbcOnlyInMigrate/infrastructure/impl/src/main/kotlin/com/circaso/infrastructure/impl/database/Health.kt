package com.circaso.infrastructure.impl.database

import org.flywaydb.core.Flyway

// Flyway is JDBC: outside database.migrate it would put a blocking connection on the app's path.
class Health(private val flyway: Flyway)
