package com.circaso.shared

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.format.DateTimeParseException

/** camelCase fields, unknown fields ignored on read, defaults written (Conventions → JSON). */
val CircasoJson: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

/** Times are ISO-8601 UTC strings on the wire (`2026-10-21T18:06:00Z`); storage uses epoch milliseconds. */
object InstantSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("Instant", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: Instant) = encoder.encodeString(value.toString())

    override fun deserialize(decoder: Decoder): Instant =
        try {
            Instant.parse(decoder.decodeString())
        } catch (e: DateTimeParseException) {
            throw SerializationException("Not an ISO-8601 UTC time", e)
        }
}
