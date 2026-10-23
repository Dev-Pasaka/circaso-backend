package com.circaso.shared

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * A phone number in E.164 (`+254712345678`), parsed once at the edge; an invalid number can't exist as a
 * model. The string form is masked, so a number that slips into a log line or an exception message is
 * never printed in full. Read [e164] only where the full number is needed (hashing, sending the SMS).
 */
@Serializable(with = PhoneNumber.Serializer::class)
@JvmInline
value class PhoneNumber private constructor(val e164: String) {

    /** `+254 7•• ••• 678`: country code, first digit of the national number, last three digits. */
    fun masked(): String {
        val digits = e164.drop(1)
        val countryCode = digits.take(countryCodeLength(digits))
        val national = digits.drop(countryCode.length)
        if (national.length <= 4) return "+$countryCode ${"•".repeat(national.length)}"
        val middle = national.length - 4
        val hidden = if (middle >= 3) "${"•".repeat(middle - 3)} •••" else "•".repeat(middle)
        return "+$countryCode ${national.first()}$hidden ${national.takeLast(3)}"
    }

    override fun toString(): String = masked()

    companion object {
        private val e164Pattern = Regex("^\\+[1-9][0-9]{6,14}$")

        /** Strict E.164 only: a leading `+`, no spaces or punctuation, 7 to 15 digits. */
        fun parse(raw: String): PhoneNumber? = raw.trim().takeIf(e164Pattern::matches)?.let(::PhoneNumber)

        /**
         * Length of the calling code. Calling codes are prefix-free, but a full table isn't worth carrying
         * yet: the markets are African (`2xx`, except Egypt `20` and South Africa `27`), and for the rest
         * the common one-digit codes (`1`, `7`) and otherwise a mask that hides more rather than less.
         */
        private fun countryCodeLength(digits: String): Int = when {
            digits.startsWith("20") || digits.startsWith("27") -> 2
            digits.startsWith("2") -> 3
            digits.startsWith("1") || digits.startsWith("7") -> 1
            else -> 3
        }
    }

    object Serializer : KSerializer<PhoneNumber> {
        override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("PhoneNumber", PrimitiveKind.STRING)

        override fun serialize(encoder: Encoder, value: PhoneNumber) = encoder.encodeString(value.e164)

        override fun deserialize(decoder: Decoder): PhoneNumber =
            parse(decoder.decodeString()) ?: throw SerializationException("Not an E.164 phone number")
    }
}
