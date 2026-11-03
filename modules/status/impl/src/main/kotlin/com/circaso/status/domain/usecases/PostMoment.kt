package com.circaso.status.domain.usecases

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import com.circaso.status.api.MomentPostedFrame
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.models.AudienceMember
import com.circaso.status.domain.models.Moment
import com.circaso.status.domain.models.PostMomentCommand
import com.circaso.status.domain.models.PostedMoment
import com.circaso.status.domain.ports.FrameSender
import com.circaso.status.domain.ports.Transactor
import com.circaso.status.domain.repositories.MediaRepository
import com.circaso.status.domain.repositories.MomentRepository
import com.circaso.status.domain.support.MomentReach
import com.circaso.status.domain.support.OrderedIds
import com.circaso.status.domain.support.StatusConfig
import com.circaso.status.domain.support.decodedSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * ST3 `POST /moments`: one moment, encrypted once under the poster's sender key, for the audience the poster's phone
 * chose. People who can't reach the poster (or whom the poster can't reach) are dropped silently, with their key
 * copies, so a block is never revealed. The (poster, `clientMomentId`) claim makes a retry return the first answer.
 * The moment, its audience and its file are written in one transaction; `status.posted` goes out after it, in the
 * background, so a 1,024-person audience doesn't hold the answer.
 */
class PostMoment(
    private val moments: MomentRepository,
    private val media: MediaRepository,
    private val transactor: Transactor,
    private val reach: MomentReach,
    private val ids: OrderedIds,
    private val frames: FrameSender,
    private val background: CoroutineScope,
    private val config: StatusConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(command: PostMomentCommand): Resource<PostedMoment> {
        validate(command)?.let { return Resource.error(it) }
        moments.findByClientId(command.poster, command.clientMomentId)?.let { return Resource.success(it.posted()) }

        val now = clock.instant()
        rateLimit(command.poster, now)?.let { return Resource.error(it) }

        val kept = reach.mutual(command.poster, command.audience.distinct())
        val copies = command.keyCopies.associate { it.userId to it.ciphertext }
        val moment =
            Moment(
                momentId = MomentId(ids.next()),
                posterId = command.poster,
                clientMomentId = command.clientMomentId,
                ciphertext = command.ciphertext,
                mediaId = command.mediaIds.firstOrNull(),
                postedAt = now,
                expiresAt = now.plus(config.momentLifetime),
            )
        val audience = kept.map { AudienceMember(it, copies[it]) }

        val written =
            when (val result = write(moment, audience, now)) {
                is Resource.Success -> result.data
                is Resource.Error -> return result
            }
        if (!written) {
            // a retry with the same clientMomentId won the claim between our check and our insert
            val first = moments.findByClientId(command.poster, command.clientMomentId)
            return first?.let { Resource.success(it.posted()) } ?: Resource.error(StatusError.MomentNotFound)
        }

        if (kept.isNotEmpty()) {
            background.launch { frames.send(kept, MomentPostedFrame(moment.momentId, moment.posterId)) }
        }
        return Resource.success(moment.posted())
    }

    private fun validate(command: PostMomentCommand): StatusError? {
        val audience = command.audience.toSet()
        val copied = command.keyCopies.map { it.userId }
        return when {
            audience.size !in 1..config.audienceMax || command.poster in audience -> StatusError.AudienceInvalid
            copied.size != copied.toSet().size || !audience.containsAll(copied) -> StatusError.AudienceInvalid
            decodedSize(command.ciphertext) > config.ciphertextMaxBytes -> StatusError.MomentTooLarge
            command.keyCopies.any { decodedSize(it.ciphertext) > config.keyCopyMaxBytes } -> StatusError.MomentTooLarge
            command.mediaIds.size > config.mediaPerMomentMax -> StatusError.MediaInvalid
            command.mediaIds.size != command.mediaIds.toSet().size -> StatusError.MediaInvalid
            else -> null
        }
    }

    /** More than [StatusConfig.momentsPerWindow] in the window: wait until the oldest of them leaves it. */
    private suspend fun rateLimit(poster: UserId, now: Instant): StatusError? {
        val (count, oldest) = moments.postedSince(poster, now.minus(config.rateWindow))
        if (count < config.momentsPerWindow) return null
        val freesAt = (oldest ?: now).plus(config.rateWindow)
        val seconds = Duration.between(now, freesAt).seconds.coerceAtLeast(1)
        return StatusError.TooManyMoments(seconds)
    }

    /** False when the claim was already taken; an unusable file rolls the whole moment back. */
    private suspend fun write(
        moment: Moment,
        audience: List<AudienceMember>,
        now: Instant,
    ): Resource<Boolean> =
        transactor.attempt("moments.post") {
            if (!moments.insert(moment, audience)) return@attempt Resource.success(false)
            val mediaId = moment.mediaId
            if (mediaId != null && !media.attach(mediaId, moment.posterId, moment.momentId, now, moment.expiresAt)) {
                return@attempt Resource.error(StatusError.MediaNotFound)
            }
            Resource.success(true)
        }

    private fun Moment.posted() = PostedMoment(momentId, postedAt, expiresAt)
}
