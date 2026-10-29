package com.circaso.chama.api

import com.circaso.shared.Uuid7
import com.circaso.shared.UuidIdSerializer
import kotlinx.serialization.Serializable
import java.util.UUID

/** One payment request in a chama (a group's request to pay someone from what it holds). */
@Serializable(with = RequestId.Serializer::class)
@JvmInline
value class RequestId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): RequestId = RequestId(Uuid7.generate())

        fun parse(raw: String): RequestId? = Uuid7.parse(raw)?.let(::RequestId)
    }

    object Serializer : UuidIdSerializer<RequestId>("RequestId", ::RequestId, RequestId::value)
}
