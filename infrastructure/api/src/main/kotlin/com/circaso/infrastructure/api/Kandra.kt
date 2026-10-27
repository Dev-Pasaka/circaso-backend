package com.circaso.infrastructure.api

import io.kandra.migrate.KandraMigration
import kotlin.reflect.KClass

/**
 * What a module still on ScyllaDB contributes to the one Kandra install: its keyspace, its entities and its
 * migrations. `app` collects every module's spec; infrastructure installs Kandra once and runs one migration runner per
 * keyspace. There is no default keyspace, so Kandra refuses to start if an entity forgets to name one.
 *
 * Temporary: each module moves to [DatabaseModuleSpec] in its own slice (PG2–PG5), and PG6 deletes this.
 */
data class KandraModuleSpec(
    /** `circaso_<module>`. */
    val keyspace: String,
    val entities: List<KClass<*>>,
    /** Applied in version order by `app migrate`, with their own history per keyspace. */
    val migrations: List<KandraMigration> = emptyList(),
)
