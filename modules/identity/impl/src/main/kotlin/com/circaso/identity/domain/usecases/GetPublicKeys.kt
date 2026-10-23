package com.circaso.identity.domain.usecases

import com.circaso.identity.domain.ports.PublicKey
import com.circaso.identity.domain.ports.TokenIssuer
import com.circaso.shared.Resource

/** `GET /.well-known/jwks.json`: the keys access tokens are signed with, so a new key can be introduced without signing anyone out. */
class GetPublicKeys(private val tokens: TokenIssuer) {
    suspend operator fun invoke(): Resource<List<PublicKey>> = Resource.success(tokens.publicKeys())
}
