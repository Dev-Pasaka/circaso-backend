package com.circaso.status

import com.circaso.shared.UserId
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.models.MediaKind
import com.circaso.status.domain.models.MediaState
import com.circaso.status.domain.models.PrepareMediaCommand
import com.circaso.status.domain.support.StatusConfig
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ST1, ST2, ST8 and the file sweep against in-memory fakes. */
class MomentMediaTest {
    private val h = StatusHarness()
    private val poster = UserId.new()
    private val amina = UserId.new()
    private val baraka = UserId.new()

    @Test
    fun `an upload is 1 byte to 64 MiB of ciphertext`() =
        runTest {
            h.prepareMedia(PrepareMediaCommand(poster, 0, MediaKind.IMAGE)).assertError(StatusError.MediaInvalid)
            h
                .prepareMedia(PrepareMediaCommand(poster, 64L * 1_024 * 1_024 + 1, MediaKind.VIDEO))
                .assertError(StatusError.MediaInvalid)

            val upload = h.prepareMedia(PrepareMediaCommand(poster, 64L * 1_024 * 1_024, MediaKind.VIDEO)).data()

            assertEquals("application/octet-stream", upload.contentType)
            // The upload link lasts an hour (64 MiB on a slow connection), and the file two unless it is attached.
            assertEquals(h.clock.now.plus(Duration.ofHours(1)), upload.expiresAt)
            assertEquals(MediaState.PENDING, h.media.find(upload.mediaId)?.state)
            assertEquals(h.clock.now.plus(Duration.ofHours(2)), h.media.find(upload.mediaId)?.expiresAt)
        }

    @Test
    fun `the old 32 MiB limit no longer refuses a video, and an older app's 32 MiB still goes`() =
        runTest {
            h.prepareMedia(PrepareMediaCommand(poster, 32L * 1_024 * 1_024 + 1, MediaKind.VIDEO)).data()
            h.prepareMedia(PrepareMediaCommand(poster, 32L * 1_024 * 1_024 - 36, MediaKind.VIDEO)).data()
        }

    @Test
    fun `the limit is the server's own setting`() =
        runTest {
            val small = StatusHarness(StatusConfig(mediaMaxBytes = 1_000))

            small
                .prepareMedia(
                    PrepareMediaCommand(poster, 1_001, MediaKind.VIDEO),
                ).assertError(StatusError.MediaInvalid)
            small.prepareMedia(PrepareMediaCommand(poster, 1_000, MediaKind.VIDEO)).data()
        }

    @Test
    fun `completing checks the size, is the poster's only, and answers the same twice`() =
        runTest {
            val upload = h.prepareMedia(PrepareMediaCommand(poster, 100, MediaKind.VOICE)).data()

            h.completeMedia(poster, upload.mediaId).assertError(StatusError.MediaInvalid)
            h.storage.put(poster, upload.mediaId, 99)
            h.completeMedia(poster, upload.mediaId).assertError(StatusError.MediaInvalid)
            h.storage.put(poster, upload.mediaId, 100)
            h.completeMedia(amina, upload.mediaId).assertError(StatusError.MediaNotFound)

            assertEquals(MediaState.READY, h.completeMedia(poster, upload.mediaId).data().state)
            assertEquals(MediaState.READY, h.completeMedia(poster, upload.mediaId).data().state)
        }

    @Test
    fun `a moment takes only a ready file of the poster's that no other moment carries, all or nothing`() =
        runTest {
            val pending = h.prepareMedia(PrepareMediaCommand(poster, 10, MediaKind.IMAGE)).data().mediaId
            h.post(h.command(poster, listOf(amina), mediaIds = listOf(pending))).assertError(StatusError.MediaNotFound)
            val someoneElses = h.readyMedia(amina)
            h
                .post(h.command(poster, listOf(baraka), mediaIds = listOf(someoneElses)))
                .assertError(StatusError.MediaNotFound)
            assertTrue(h.moments.moments.isEmpty())

            val mediaId = h.readyMedia(poster)
            val posted = h.post(h.command(poster, listOf(amina), mediaIds = listOf(mediaId))).data()
            assertEquals(posted.expiresAt, h.media.find(mediaId)?.expiresAt)
            h.post(h.command(poster, listOf(amina), mediaIds = listOf(mediaId))).assertError(StatusError.MediaNotFound)
            assertEquals(1, h.moments.moments.size)
        }

    @Test
    fun `the poster and the live moment's audience can download, nobody else, and not after it expires`() =
        runTest {
            val mediaId = h.readyMedia(poster)
            h.post(h.command(poster, listOf(amina), mediaIds = listOf(mediaId))).data()

            assertEquals(4_096, h.download(amina, mediaId).data().sizeBytes)
            assertNotNull(h.download(poster, mediaId).data().downloadUrl)
            h.download(baraka, mediaId).assertError(StatusError.MediaNotFound)
            h.download(amina, UUID.randomUUID()).assertError(StatusError.MediaNotFound)

            h.reach.blocked += setOf(amina, poster)
            h.download(amina, mediaId).assertError(StatusError.MediaNotFound)
            h.reach.blocked.clear()

            h.clock.advance(Duration.ofHours(24))
            h.download(amina, mediaId).assertError(StatusError.MediaNotFound)
            h.download(poster, mediaId).assertError(StatusError.MediaNotFound)
        }

    @Test
    fun `the sweep deletes files unattached after two hours and those whose moment expired, and keeps the rest`() =
        runTest {
            val unattached = h.readyMedia(poster)
            val attached = h.readyMedia(poster)
            h.post(h.command(poster, listOf(amina), mediaIds = listOf(attached))).data()

            h.clock.advance(Duration.ofMinutes(119))
            assertEquals(0, h.purgeMedia())
            h.clock.advance(Duration.ofMinutes(1))
            assertEquals(1, h.purgeMedia())
            assertNull(h.media.find(unattached))
            assertFalse(h.storage.has(poster, unattached))
            assertNotNull(h.media.find(attached))

            h.clock.advance(Duration.ofHours(23))
            h.storage.failing = true
            assertEquals(0, h.purgeMedia())
            assertNotNull(h.media.find(attached))
            h.storage.failing = false
            assertEquals(1, h.purgeMedia())
            assertNull(h.media.find(attached))
        }
}
