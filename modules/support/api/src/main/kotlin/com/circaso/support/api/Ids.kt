package com.circaso.support.api

import com.circaso.shared.UuidIdSerializer
import com.circaso.shared.Uuid7
import kotlinx.serialization.Serializable
import java.util.UUID

/** One support ticket. UUIDv7, made on the server; the person-readable name is its [OpenedTicket.number]. */
@Serializable(with = TicketId.Serializer::class)
@JvmInline
value class TicketId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): TicketId = TicketId(Uuid7.generate())

        fun parse(raw: String?): TicketId? = raw?.let(Uuid7::parse)?.let(::TicketId)
    }

    object Serializer : UuidIdSerializer<TicketId>("TicketId", ::TicketId, TicketId::value)
}

/** `CS-000123`: what a person reads out and Asha tells the user. Six digits at least, more when the count passes it. */
object TicketNumbers {
    fun format(number: Long): String = "CS-" + number.toString().padStart(6, '0')
}
