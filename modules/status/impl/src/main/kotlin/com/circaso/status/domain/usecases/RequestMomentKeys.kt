package com.circaso.status.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.api.MomentKeyRequestedFrame
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.ports.FrameSender
import com.circaso.status.domain.ports.KeyRequestThrottle
import com.circaso.status.domain.repositories.MomentRepository
import com.circaso.status.domain.support.MomentReach
import com.circaso.status.domain.support.StatusConfig
import java.time.Clock

/**
 * `POST /moments/keys/request`: the caller can't open some posters' moments (a new phone means a new Signal
 * identity, so the copies sent before were sealed for the old one). Each poster with a live moment shown to the
 * caller, whom neither has blocked since, gets `status.key_requested`, at most once per
 * [StatusConfig.keyRequestInterval] per pair. Anyone else is skipped without a word, so the answer reveals nothing
 * ST4 doesn't. Answers the posters asked.
 */
class RequestMomentKeys(
    private val moments: MomentRepository,
    private val reach: MomentReach,
    private val throttle: KeyRequestThrottle,
    private val frames: FrameSender,
    private val config: StatusConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(requester: UserId, posters: List<UserId>): Resource<List<UserId>> {
        val distinct = posters.distinct()
        val invalid =
            distinct.size !in 1..config.keyRequestPostersMax || distinct.size != posters.size || requester in distinct
        if (invalid) return Resource.error(StatusError.KeysInvalid)

        val showing = moments.postersShowing(requester, distinct, clock.instant())
        val asked = mutableListOf<UserId>()
        for (poster in distinct) {
            if (poster !in showing || !reach.stillVisible(requester, poster)) continue
            if (!throttle.claim(requester, poster, config.keyRequestInterval)) continue
            frames.send(listOf(poster), MomentKeyRequestedFrame(requester))
            asked += poster
        }
        return Resource.success(asked)
    }
}
