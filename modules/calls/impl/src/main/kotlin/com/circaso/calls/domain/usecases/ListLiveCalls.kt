package com.circaso.calls.domain.usecases

import com.circaso.calls.domain.models.CallView
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.calls.domain.support.CallViews
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/**
 * CL2c `GET /calls/live`: every call ringing or active in a chat the person was rung for (2026-11-09), so the chats
 * list can show "Call in progress" on each of them in one read, and a phone that missed `call.ended` while its socket
 * was down drops what is over. The frames carry no ICE servers and neither does this: it is for showing, not joining.
 */
class ListLiveCalls(private val calls: CallRepository, private val views: CallViews) {
    suspend operator fun invoke(userId: UserId): Resource<List<CallView>> =
        Resource.success(calls.liveFor(userId).sortedBy { it.createdAt }.map { views.frameView(it) })
}
