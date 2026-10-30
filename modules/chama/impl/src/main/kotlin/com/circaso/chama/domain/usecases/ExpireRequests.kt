package com.circaso.chama.domain.usecases

import com.circaso.chama.domain.repositories.RequestRepository
import java.time.Clock

/** `chama.expire-requests`: a request nobody decided within 48 hours is `EXPIRED`. Runs every few minutes. */
class ExpireRequests(private val requests: RequestRepository, private val clock: Clock) {
    suspend operator fun invoke(): Int = requests.expire(clock.instant())
}
