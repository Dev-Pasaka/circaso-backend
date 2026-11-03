package com.circaso.status.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.api.MomentKeysReadyFrame
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.models.MomentKeyCopy
import com.circaso.status.domain.ports.FrameSender
import com.circaso.status.domain.ports.Transactor
import com.circaso.status.domain.repositories.MomentRepository
import com.circaso.status.domain.support.MomentReach
import com.circaso.status.domain.support.StatusConfig
import com.circaso.status.domain.support.decodedSize
import java.time.Clock

/**
 * `POST /moments/key-copies`: the poster's answer to `status.key_requested`. Each copy replaces one person's
 * `keyCopy` on one of the poster's own live moments, when that person is in its audience and neither has blocked the
 * other; the rest are skipped without a word. Everyone who got at least one copy is sent `status.keys_ready` and
 * reads ST4 again. Repeating the call is harmless: the copies are simply replaced. Answers how many were taken.
 */
class ShareMomentKeyCopies(
    private val moments: MomentRepository,
    private val transactor: Transactor,
    private val reach: MomentReach,
    private val frames: FrameSender,
    private val config: StatusConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(poster: UserId, copies: List<MomentKeyCopy>): Resource<Int> {
        val pairs = copies.map { it.momentId to it.userId }
        val invalid =
            copies.size !in 1..config.keyCopiesMax ||
                pairs.size != pairs.toSet().size ||
                copies.any { it.userId == poster || decodedSize(it.ciphertext) > config.resealedCopyMaxBytes }
        if (invalid) return Resource.error(StatusError.KeysInvalid)

        val now = clock.instant()
        val people =
            copies
                .map { it.userId }
                .distinct()
                .filter { reach.stillVisible(it, poster) }
                .toSet()
        val mine =
            copies
                .map { it.momentId }
                .distinct()
                .filter { moments.findLive(it, now)?.posterId == poster }
                .toSet()
        val reached =
            transactor.run("moments.key-copies") {
                copies
                    .filter { it.momentId in mine && it.userId in people }
                    .filter { moments.replaceKeyCopy(it.momentId, it.userId, it.ciphertext) }
            }
        val told = reached.map { it.userId }.distinct()
        if (told.isNotEmpty()) frames.send(told, MomentKeysReadyFrame(poster))
        return Resource.success(reached.size)
    }
}
