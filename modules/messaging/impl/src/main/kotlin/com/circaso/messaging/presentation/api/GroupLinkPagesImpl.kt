package com.circaso.messaging.presentation.api

import com.circaso.messaging.api.GroupLinkPage
import com.circaso.messaging.api.GroupLinkPages
import com.circaso.messaging.api.GroupLinkPhoto
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.messaging.presentation.services.GroupLinkService
import com.circaso.shared.getOrNull

/** messaging:api for share, which draws a join link's public page and serves its photo (decision 2026-11-14). */
class GroupLinkPagesImpl(private val links: GroupLinkService) : GroupLinkPages {
    override suspend fun page(token: GroupLinkToken): GroupLinkPage? = links.page(token).getOrNull()

    override suspend fun photo(token: GroupLinkToken): GroupLinkPhoto? = links.photo(token).getOrNull()
}
