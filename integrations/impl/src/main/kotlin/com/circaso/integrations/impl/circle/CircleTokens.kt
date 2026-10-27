package com.circaso.integrations.impl.circle

import com.circaso.shared.Resource
import java.util.concurrent.ConcurrentHashMap

/**
 * Circle's token ids, resolved to the token's chain and contract address. A transaction (and so a webhook) names its
 * token only by Circle's id, while we configure USDC by address; a token id never changes what it points to, so each
 * is fetched once per node and kept. A native coin (ETH) has no contract address and is never USDC.
 */
internal class CircleTokens(private val client: CircleClient) {
    private val known = ConcurrentHashMap<String, TokenData>()

    suspend fun resolve(tokenId: String): Resource<TokenData> {
        known[tokenId]?.let { return Resource.success(it) }
        val reply = client.get<TokenLookupData>("/v1/w3s/tokens/$tokenId", "get_token")
        if (reply !is CircleReply.Ok) return CircleErrors.failure(reply, "get_token")

        val token = reply.data.token ?: return CircleErrors.failure(CircleReply.Failed(200, null), "get_token")
        known[tokenId] = token
        return Resource.success(token)
    }
}
