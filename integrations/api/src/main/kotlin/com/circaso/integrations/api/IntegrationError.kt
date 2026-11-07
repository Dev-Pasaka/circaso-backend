package com.circaso.integrations.api

import com.circaso.shared.DomainError
import com.circaso.shared.ErrorDetails
import com.circaso.shared.ErrorKind

/**
 * How every integration fails, in our language: vendor classes and vendor error codes never cross a contract.
 * A module maps these to its own codes (`identity.sms_unavailable`).
 */
sealed class IntegrationError(override val code: String, override val message: String, override val kind: ErrorKind) :
    DomainError {
    /**
     * The vendor is down, slow, refused us or its circuit is open; trying again later may work.
     *
     * [isTransient] says whether trying again at once may work (a dropped connection, an overloaded or failing
     * server, a rate limit), as opposed to a fault that waits for us (a key it no longer accepts, an open circuit).
     * [retryAfterSeconds] is the vendor's own `Retry-After`, when it sent one.
     */
    class Unavailable(vendor: String, val isTransient: Boolean = true, val retryAfterSeconds: Long? = null) :
        IntegrationError("integrations.unavailable", "$vendor is unavailable.", ErrorKind.UPSTREAM) {
        override val details: ErrorDetails?
            get() = retryAfterSeconds?.let { ErrorDetails(retryAfterSeconds = it) }
    }

    /** The vendor looked at what we sent and refused it (a number it can't reach, a token it doesn't accept). */
    class Rejected(message: String) : IntegrationError("integrations.rejected", message, ErrorKind.INVALID)

    /** The provider can't work in this language (a voice that speaks English only, asked for Swahili). */
    class UnsupportedLanguage(language: String) :
        IntegrationError(
            "integrations.unsupported_language",
            "The provider doesn't support the language '$language'.",
            ErrorKind.INVALID,
        )

    /** Custody: the user typed a wrong PIN too often and PIN entry is locked for a while. */
    class PinLocked :
        IntegrationError("integrations.custody_pin_locked", "PIN entry is locked for now.", ErrorKind.INVALID)

    /** Custody: the wallet holds less than the transfer needs. */
    class InsufficientFunds :
        IntegrationError(
            "integrations.custody_insufficient_funds",
            "The wallet doesn't hold enough for this transfer.",
            ErrorKind.INVALID,
        )

    /** Custody: the destination isn't a valid address on the wallet's chain. */
    class AddressInvalid :
        IntegrationError(
            "integrations.custody_address_invalid",
            "The address isn't valid on this chain.",
            ErrorKind.INVALID,
        )

    /** Custody: the provider can't send this coin on this chain (a coin it doesn't know there). */
    class AssetNotSupported :
        IntegrationError(
            "integrations.custody_asset_not_supported",
            "The custody provider can't send this coin on this chain.",
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
