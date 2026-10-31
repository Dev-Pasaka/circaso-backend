package com.circaso.assistant.domain.usecases

import com.circaso.assistant.domain.models.Usage
import com.circaso.assistant.domain.support.UsageReader
import com.circaso.shared.Resource
import com.circaso.shared.UserId

/** A7: today's allowance used and left, and the plan. */
class GetUsage(private val usage: UsageReader) {
    suspend operator fun invoke(userId: UserId): Resource<Usage> = Resource.success(usage.today(userId))
}
