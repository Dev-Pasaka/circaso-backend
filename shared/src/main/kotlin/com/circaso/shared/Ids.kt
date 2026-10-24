package com.circaso.shared

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.util.UUID

/** Writes an id as its canonical UUID string and reads it back strictly. */
abstract class UuidIdSerializer<T>(name: String, private val wrap: (UUID) -> T, private val unwrap: (T) -> UUID) :
    KSerializer<T> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor(name, PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: T) = encoder.encodeString(unwrap(value).toString())

    override fun deserialize(decoder: Decoder): T {
        val raw = decoder.decodeString()
        return wrap(Uuid7.parse(raw) ?: throw SerializationException("'${descriptor.serialName}' is not a UUID"))
    }
}

@Serializable(with = UserId.Serializer::class)
@JvmInline
value class UserId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): UserId = UserId(Uuid7.generate())

        fun parse(raw: String): UserId? = Uuid7.parse(raw)?.let(::UserId)
    }

    object Serializer : UuidIdSerializer<UserId>("UserId", ::UserId, UserId::value)
}

@Serializable(with = DeviceId.Serializer::class)
@JvmInline
value class DeviceId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): DeviceId = DeviceId(Uuid7.generate())

        fun parse(raw: String): DeviceId? = Uuid7.parse(raw)?.let(::DeviceId)
    }

    object Serializer : UuidIdSerializer<DeviceId>("DeviceId", ::DeviceId, DeviceId::value)
}

@Serializable(with = ConversationId.Serializer::class)
@JvmInline
value class ConversationId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): ConversationId = ConversationId(Uuid7.generate())

        fun parse(raw: String): ConversationId? = Uuid7.parse(raw)?.let(::ConversationId)
    }

    object Serializer : UuidIdSerializer<ConversationId>("ConversationId", ::ConversationId, ConversationId::value)
}

/** Identifies one domain event; consumers record it to skip repeats. */
@Serializable(with = EventId.Serializer::class)
@JvmInline
value class EventId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): EventId = EventId(Uuid7.generate())

        fun parse(raw: String): EventId? = Uuid7.parse(raw)?.let(::EventId)
    }

    object Serializer : UuidIdSerializer<EventId>("EventId", ::EventId, EventId::value)
}
