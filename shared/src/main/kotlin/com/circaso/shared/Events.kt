package com.circaso.shared

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer
import java.time.Instant

/**
 * Something that happened, declared in the publisher's `api`, named in the past tense, carrying ids and
 * the facts that changed. Tokens, device keys, provider subjects, emails and raw phone numbers never go in one.
 */
interface DomainEvent {
    /** `<producer>.<Name>`, for example `identity.AccountActivated`. */
    val type: String

    /** Fields are only ever added within a version; a breaking change publishes the next version alongside. */
    val version: Int get() = 1
}

/** The module that publishes the event, which is also the name of its stream: `events:<producer>`. */
val DomainEvent.producer: String get() = type.substringBefore('.')

/** What is stored in the outbox row and written to the stream (Architecture → Durable events → Envelope). */
@Serializable
data class EventEnvelope(
    val eventId: EventId,
    val type: String,
    val version: Int,
    @Serializable(with = InstantSerializer::class) val occurredAt: Instant,
    val producer: String,
    /** W3C trace context, so a consumer's handler continues the trace of the request that caused the event. */
    val traceparent: String? = null,
    val data: JsonElement,
) {
    val stream: String get() = streamOf(producer)

    fun toJson(): String = CircasoJson.encodeToString(Companion.serializer(), this)

    companion object {
        fun streamOf(producer: String): String = "events:$producer"

        fun fromJson(raw: String): EventEnvelope = CircasoJson.decodeFromString(serializer(), raw)

        fun <E : DomainEvent> of(
            event: E,
            eventSerializer: KSerializer<E>,
            eventId: EventId = EventId.new(),
            occurredAt: Instant = Instant.now(),
            traceparent: String? = null,
        ): EventEnvelope =
            EventEnvelope(
                eventId = eventId,
                type = event.type,
                version = event.version,
                occurredAt = occurredAt,
                producer = event.producer,
                traceparent = traceparent,
                data = CircasoJson.encodeToJsonElement(eventSerializer, event),
            )
    }
}

/** Decodes the payload of a received envelope back into its event type. */
fun <E : DomainEvent> EventEnvelope.decode(eventSerializer: KSerializer<E>): E =
    CircasoJson.decodeFromJsonElement(eventSerializer, data)

inline fun <reified E : DomainEvent> EventEnvelope.decode(): E = decode(serializer<E>())

/** Handlers are idempotent and tolerate reordering: compare a version or timestamp before applying. */
fun interface EventHandler {
    suspend fun handle(envelope: EventEnvelope)
}

/** One consumer group's interest in one stream: the handler for each event type it reacts to. */
data class EventSubscription(
    /** The subscribing module's name, used as the Redis consumer group. */
    val group: String,
    /** The publisher's stream, for example `events:identity`. */
    val stream: String,
    /** Event type → handler. Types not listed are acknowledged and ignored. */
    val handlers: Map<String, EventHandler>,
)

/**
 * How modules tell each other that something happened. The implementation lives in infrastructure
 * (outbox relay, Redis Streams); modules only see this.
 */
interface EventBus {
    /**
     * Stamps an event with an id, time and trace context. The use case stores the result in its module's
     * outbox row, in the same batch as the change that caused it.
     */
    fun <E : DomainEvent> envelope(event: E, eventSerializer: KSerializer<E>): EventEnvelope

    /**
     * Called right after that batch succeeded: writes the envelope to the stream, then stamps `publishedAt`
     * on the outbox row. Never throws; if it fails, the outbox relay publishes the event later, so a
     * consumer may see a duplicate and handlers skip those.
     */
    suspend fun publish(envelope: EventEnvelope)

    /** Starts consuming: `XREADGROUP`, `XACK` only after the handler succeeds, retry, then dead-letter. */
    fun subscribe(subscription: EventSubscription)
}

inline fun <reified E : DomainEvent> EventBus.envelope(event: E): EventEnvelope = envelope(event, serializer<E>())
