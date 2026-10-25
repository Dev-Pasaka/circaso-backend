package com.circaso.integrations.api

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind

/**
 * How every integration fails, in our language: vendor classes and vendor error codes never cross a contract.
 * A module maps these to its own codes (`identity.sms_unavailable`).
 */
sealed class IntegrationError(override val code: String, override val message: String, override val kind: ErrorKind) :
    DomainError {
    /** The vendor is down, slow, refused us or its circuit is open; trying again later may work. */
    class Unavailable(vendor: String) :
        IntegrationError("integrations.unavailable", "$vendor is unavailable.", ErrorKind.UPSTREAM)

    /** The vendor looked at what we sent and refused it (a number it can't reach, a token it doesn't accept). */
    class Rejected(message: String) : IntegrationError("integrations.rejected", message, ErrorKind.INVALID)

    /** The provider can't work in this language (a voice that speaks English only, asked for Swahili). */
    class UnsupportedLanguage(language: String) :
        IntegrationError(
            "integrations.unsupported_language",
            "The provider doesn't support the language '$language'.",
            ErrorKind.INVALID,
        )

    /** The configured provider has no implementation yet; only placeholders return this. */
    class NotImplemented(contract: String, provider: String) :
        IntegrationError(
            "integrations.not_implemented",
            "$contract provider '$provider' is not implemented yet.",
            ErrorKind.UPSTREAM,
        )
}
