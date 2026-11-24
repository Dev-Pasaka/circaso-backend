package com.circaso.ramp.domain.ports

/** Checks an EVM address the way the chain does: its shape, and the EIP-55 checksum when it is written in mixed case. */
fun interface EvmAddresses {
    fun check(raw: String): AddressCheck
}

sealed interface AddressCheck {
    /** A valid address, in its EIP-55 checksummed form. */
    data class Valid(val checksummed: String) : AddressCheck

    /** Why it isn't one, in words for the merchant. */
    data class Invalid(val problem: String) : AddressCheck
}
