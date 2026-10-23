package com.circaso.infrastructure.api

import com.circaso.shared.EventEnvelope
import io.kandra.migrate.KandraMigration
import java.time.Instant
import kotlin.reflect.KClass

/**
 * What a module contributes to the one Kandra install: its keyspace, its entities and its migrations.
 * `app` collects every module's spec; infrastructure installs Kandra once and runs one migration runner per
 * keyspace. There is no default keyspace, so Kandra refuses to start if an entity forgets to name one.
 */
data class KandraModuleSpec(
    /** `circaso_<module>`. */
    val keyspace: String,
    val entities: List<KClass<*>>,
    /** Applied in version order by `app migrate`, with their own history per keyspace. */
    val migrations: List<KandraMigration> = emptyList(),
)

/**
 * Implemented by each module's `data` layer over its own outbox table (`<Name>OutboxRow`), so the relay can
 * rescue events without knowing any module's tables. A row is written in the same batch as the change that
 * caused it; the bucket is [EventEnvelope.bucket].
 */
interface OutboxStore {
    /** The module that owns this outbox; its stream is `events:<producer>`. */
    val producer: String

    /** Every row of [bucket] (a minute, `2026-10-21T18:06`) that has no `publishedAt` yet. */
    suspend fun unpublished(bucket: String): List<EventEnvelope>

    /** Stamps `publishedAt` once the event is on the stream. */
    suspend fun markPublished(envelope: EventEnvelope, at: Instant)
}
