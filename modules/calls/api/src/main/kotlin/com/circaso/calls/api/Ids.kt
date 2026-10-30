package com.circaso.calls.api

import com.circaso.shared.Uuid7
import com.circaso.shared.UuidIdSerializer
import kotlinx.serialization.Serializable
import java.util.UUID

/** One voice or video call in a chat. */
@Serializable(with = CallId.Serializer::class)
@JvmInline
value class CallId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): CallId = CallId(Uuid7.generate())

        fun parse(raw: String): CallId? = Uuid7.parse(raw)?.let(::CallId)
    }

    object Serializer : UuidIdSerializer<CallId>("CallId", ::CallId, CallId::value)
}
