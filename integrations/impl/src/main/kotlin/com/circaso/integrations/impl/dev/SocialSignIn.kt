package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.IntegrationError
import com.circaso.integrations.api.SocialIdentity
import com.circaso.integrations.api.SocialProvider
import com.circaso.integrations.api.SocialSignIn
import com.circaso.shared.Resource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Accepts tokens of the form `dev:<subject>[:<email>[:<name>]]` and checks nothing else, so the sign-in flow can
 * be driven without Google or Apple. `fake` and `log` share it; local and test runs are the only users.
 */
class FakeSocialSignIn : SocialSignIn {
    val revoked: MutableList<String> = CopyOnWriteArrayList()

    override suspend fun verify(
        provider: SocialProvider,
        idToken: String,
        nonce: String,
    ): Resource<SocialIdentity> {
        val parts = idToken.split(':')
        if (parts.size < 2 || parts[0] != "dev" || parts[1].isBlank()) {
            return Resource.error(IntegrationError.Rejected("The sign-in token was not accepted."))
        }
        val email = parts.getOrNull(2)?.takeIf { it.isNotBlank() }
        return Resource.success(
            SocialIdentity(
                provider = provider,
                subject = parts[1],
                email = email,
                emailVerified = email != null,
                displayName = parts.getOrNull(3)?.takeIf { it.isNotBlank() },
                pictureUrl = null,
            ),
        )
    }

    override suspend fun revokeApple(appleToken: String): Resource<Unit> {
        revoked += appleToken
        return Resource.success(Unit)
    }
}
