package com.circaso.share.presentation.services

import com.circaso.messaging.api.GroupLinkPhoto
import com.circaso.messaging.api.GroupLinkToken
import com.circaso.share.domain.models.GroupPageLookup
import com.circaso.share.domain.models.PageLookup
import com.circaso.share.domain.models.PayLink
import com.circaso.share.domain.usecases.ReadPayLink
import com.circaso.share.domain.usecases.ViewGroupLinkPage
import com.circaso.share.domain.usecases.ViewGroupLinkPhoto
import com.circaso.share.domain.usecases.ViewSharePage
import com.circaso.shared.Resource

/** The public page behind a link, for browsers and the chat apps that draw link previews. */
class SharePageService(
    private val viewSharePage: ViewSharePage,
    private val viewGroupLinkPage: ViewGroupLinkPage,
    private val viewGroupLinkPhoto: ViewGroupLinkPhoto,
    private val readPayLink: ReadPayLink = ReadPayLink(),
) {
    suspend fun view(rawCode: String?, callerAddress: String): Resource<PageLookup> =
        viewSharePage(rawCode, callerAddress)

    /** A group join link's page (decision 2026-11-14): the preview is messaging's, the page share's. */
    suspend fun viewGroup(token: GroupLinkToken, callerAddress: String): Resource<GroupPageLookup> =
        viewGroupLinkPage(token, callerAddress)

    suspend fun groupPhoto(token: GroupLinkToken, callerAddress: String): Resource<GroupLinkPhoto?> =
        viewGroupLinkPhoto(token, callerAddress)

    /** A pay link's page (decision 2026-11-24): the link's own address, chain, coin and amount, checked. */
    suspend fun payLink(
        address: String?,
        chain: String?,
        asset: String?,
        amount: String?,
    ): Resource<PayLink> = readPayLink(address, chain, asset, amount)
}
