package com.circaso.status.api

import com.circaso.shared.Uuid7
import com.circaso.shared.UuidIdSerializer
import kotlinx.serialization.Serializable
import java.util.UUID

/** One moment: a 24-hour status post, end-to-end encrypted on the poster's phone. */
@Serializable(with = MomentId.Serializer::class)
@JvmInline
value class MomentId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): MomentId = MomentId(Uuid7.generate())

        fun parse(raw: String): MomentId? = Uuid7.parse(raw)?.let(::MomentId)
    }

    object Serializer : UuidIdSerializer<MomentId>("MomentId", ::MomentId, MomentId::value)
}
