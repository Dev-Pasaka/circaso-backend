package com.circaso.integrations.api

import com.circaso.shared.Resource

interface SocialSignIn {
    /** Checks signature, issuer, audience, expiry and [nonce] of a Google or Apple ID token. */
    suspend fun verify(
        provider: SocialProvider,
        idToken: String,
        nonce: String,
    ): Resource<SocialIdentity>

    /** Apple requires the sign-in grant to be revoked when the account is deleted. */
    suspend fun revokeApple(appleToken: String): Resource<Unit>
}

enum class SocialProvider { GOOGLE, APPLE }

/** [subject] is the provider's `sub`, the stable key of the account. */
data class SocialIdentity(
    val provider: SocialProvider,
    val subject: String,
    val email: String?,
    val emailVerified: Boolean,
    val displayName: String?,
    val pictureUrl: String?,
)
