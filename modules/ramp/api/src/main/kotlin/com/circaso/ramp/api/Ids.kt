package com.circaso.ramp.api

import com.circaso.shared.Uuid7
import com.circaso.shared.UuidIdSerializer
import kotlinx.serialization.Serializable
import java.util.UUID

/** One on-ramp or off-ramp trade. Also the reference the payment provider and the custodian echo back. */
@Serializable(with = RampOrderId.Serializer::class)
@JvmInline
value class RampOrderId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): RampOrderId = RampOrderId(Uuid7.generate())

        fun parse(raw: String): RampOrderId? = Uuid7.parse(raw)?.let(::RampOrderId)
    }

    object Serializer : UuidIdSerializer<RampOrderId>("RampOrderId", ::RampOrderId, RampOrderId::value)
}

/** A partner merchant: an account of its own, not a user account (decision 2026-11-22). */
@Serializable(with = MerchantId.Serializer::class)
@JvmInline
value class MerchantId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): MerchantId = MerchantId(Uuid7.generate())

        fun parse(raw: String): MerchantId? = Uuid7.parse(raw)?.let(::MerchantId)
    }

    object Serializer : UuidIdSerializer<MerchantId>("MerchantId", ::MerchantId, MerchantId::value)
}

/** A price held for a minute; one quote makes at most one order. */
@Serializable(with = QuoteId.Serializer::class)
@JvmInline
value class QuoteId(val value: UUID) {
    override fun toString(): String = value.toString()

    companion object {
        fun new(): QuoteId = QuoteId(Uuid7.generate())

        fun parse(raw: String): QuoteId? = Uuid7.parse(raw)?.let(::QuoteId)
    }

    object Serializer : UuidIdSerializer<QuoteId>("QuoteId", ::QuoteId, QuoteId::value)
}
