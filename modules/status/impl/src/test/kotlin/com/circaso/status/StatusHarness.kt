package com.circaso.status

import com.circaso.shared.DomainError
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.domain.models.KeyCopy
import com.circaso.status.domain.models.MediaKind
import com.circaso.status.domain.models.PostMomentCommand
import com.circaso.status.domain.models.PostedMoment
import com.circaso.status.domain.models.PrepareMediaCommand
import com.circaso.status.domain.support.MomentReach
import com.circaso.status.domain.support.MomentRemoval
import com.circaso.status.domain.support.OrderedIds
import com.circaso.status.domain.support.StatusConfig
import com.circaso.status.domain.usecases.CompleteMomentMedia
import com.circaso.status.domain.usecases.DeleteMoment
import com.circaso.status.domain.usecases.GetMomentMediaDownload
import com.circaso.status.domain.usecases.ListMoments
import com.circaso.status.domain.usecases.ListViews
import com.circaso.status.domain.usecases.PostMoment
import com.circaso.status.domain.usecases.PrepareMomentMedia
import com.circaso.status.domain.usecases.PurgeMomentMedia
import com.circaso.status.domain.usecases.RemoveAccountMoments
import com.circaso.status.domain.usecases.ViewMoment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.util.Base64
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Status' use cases over in-memory fakes of every port. Frames sent "in the background" run at once (an unconfined
 * scope), so a test sees them as soon as the call returns.
 */
class StatusHarness(val config: StatusConfig = StatusConfig()) {
    val clock = MovableClock()
    val moments = MemoryMoments()
    val media = MemoryMedia()
    val transactor = MemoryTransactor(moments, media)
    val storage = MemoryStorage()
    val frames = RecordingFrames()
    val reach = FakeReach()
    private val background = CoroutineScope(Dispatchers.Unconfined)
    private val momentReach = MomentReach(reach)
    private val removal = MomentRemoval(moments, media, transactor, storage, frames, clock)

    val prepareMedia = PrepareMomentMedia(media, transactor, storage, config, clock)
    val completeMedia = CompleteMomentMedia(media, transactor, storage, clock)
    val download = GetMomentMediaDownload(media, moments, momentReach, storage, config, clock)
    val purgeMedia = PurgeMomentMedia(media, removal, config, clock)
    val post = PostMoment(moments, media, transactor, momentReach, OrderedIds(clock), frames, background, config, clock)
    val list = ListMoments(moments, momentReach, clock)
    val view = ViewMoment(moments, transactor, momentReach, frames, clock)
    val views = ListViews(moments, clock)
    val delete = DeleteMoment(moments, removal, clock)
    val removeAccount = RemoveAccountMoments(moments, media, removal)

    val body: String = base64(48)

    fun base64(bytes: Int): String = Base64.getEncoder().encodeToString(ByteArray(bytes) { 3 })

    fun command(
        poster: UserId,
        audience: List<UserId>,
        keyCopies: List<KeyCopy> = audience.map { KeyCopy(it, base64(32)) },
        mediaIds: List<UUID> = emptyList(),
        clientMomentId: UUID = UUID.randomUUID(),
        ciphertext: String = body,
    ) = PostMomentCommand(poster, clientMomentId, ciphertext, audience, keyCopies, mediaIds)

    suspend fun posted(poster: UserId, audience: UserId): PostedMoment = post(command(poster, listOf(audience))).data()

    /** A completed file of [poster]'s, ready to post. */
    suspend fun readyMedia(poster: UserId, sizeBytes: Long = 4_096): UUID {
        val upload = prepareMedia(PrepareMediaCommand(poster, sizeBytes, MediaKind.IMAGE)).data()
        storage.put(poster, upload.mediaId, sizeBytes)
        completeMedia(poster, upload.mediaId).data()
        return upload.mediaId
    }
}

fun <T> Resource<T>.data(): T = assertIs<Resource.Success<T>>(this).data

fun Resource<*>.assertError(expected: DomainError) {
    assertEquals(expected.code, assertIs<Resource.Error>(this).error.code)
}
