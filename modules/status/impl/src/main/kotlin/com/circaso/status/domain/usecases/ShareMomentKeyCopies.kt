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
 * reads ST4 again.
 *
 * [answered] names the people whose request this answers, copies or not: a poster's phone can only seal again the
 * moments it posted itself, so for moments from the poster's earlier phone it has nothing to send. Each of them who
 * is still shown one of the poster's live moments is sent `status.keys_ready` too, so their phone stops waiting and
 * says those moments can't be opened there. Repeating the call is harmless: the copies are simply replaced. Answers
 * how many copies were taken.
 */
class ShareMomentKeyCopies(
    private val moments: MomentRepository,
    private val transactor: Transactor,
    private val reach: MomentReach,
    private val frames: FrameSender,
    private val config: StatusConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(
        poster: UserId,
        copies: List<MomentKeyCopy>,
        answered: List<UserId> = emptyList(),
    ): Resource<Int> {
        val pairs = copies.map { it.momentId to it.userId }
        val invalid =
            copies.size > config.keyCopiesMax ||
                answered.size > config.keyRequestPostersMax ||
                (copies.isEmpty() && answered.isEmpty()) ||
                pairs.size != pairs.toSet().size ||
                answered.size != answered.toSet().size ||
                poster in answered ||
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
            if (copies.isEmpty()) {
                emptyList()
            } else {
                transactor.run("moments.key-copies") {
                    copies
                        .filter { it.momentId in mine && it.userId in people }
                        .filter { moments.replaceKeyCopy(it.momentId, it.userId, it.ciphertext) }
                }
            }
        val copied = reached.map { it.userId }.toSet()
        // Told without a copy only while still shown one of my live moments, so an answer reveals nothing ST4 doesn't.
        val answeredOnly =
            answered.filter {
                it !in copied && reach.stillVisible(it, poster) &&
                    poster in moments.postersShowing(it, listOf(poster), now)
            }
        val told = copied.toList() + answeredOnly
        if (told.isNotEmpty()) frames.send(told, MomentKeysReadyFrame(poster))
        return Resource.success(reached.size)
    }
}
