package com.circaso.share.presentation.services

import com.circaso.share.domain.models.PageLookup
import com.circaso.share.domain.usecases.ViewSharePage
import com.circaso.shared.Resource

/** The public page behind a link, for browsers and the chat apps that draw link previews. */
class SharePageService(private val viewSharePage: ViewSharePage) {
    suspend fun view(rawCode: String?, callerAddress: String): Resource<PageLookup> =
        viewSharePage(rawCode, callerAddress)
}
