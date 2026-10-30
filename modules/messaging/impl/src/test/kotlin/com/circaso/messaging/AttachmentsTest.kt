package com.circaso.messaging

import com.circaso.messaging.api.ChatId
import com.circaso.messaging.domain.models.AttachmentKind
import com.circaso.messaging.domain.models.AttachmentState
import com.circaso.messaging.domain.models.PrepareAttachmentCommand
import com.circaso.messaging.domain.usecases.CompleteAttachmentUpload
import com.circaso.messaging.domain.usecases.GetAttachmentDownload
import com.circaso.messaging.domain.usecases.PrepareAttachmentUpload
import com.circaso.messaging.domain.usecases.PurgeAttachments
import com.circaso.messaging.presentation.services.AttachmentService
import com.circaso.shared.UserId
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** AT1 to AT3 and `messaging.purge-attachments`, over the chat fakes and in-memory storage. */
class AttachmentsTest {
    private val amina = UserId.new()
    private val brian = UserId.new()
    private val chidi = UserId.new()

    private val h = ChatHarness()
    private val attachments = MemoryAttachments()
    private val storage = MemoryAttachmentStorage()
    private val service =
        AttachmentService(
            PrepareAttachmentUpload(attachments, h.transactor, h.membership, storage, h.config, h.clock),
            CompleteAttachmentUpload(attachments, h.transactor, storage, h.clock),
            GetAttachmentDownload(attachments, h.membership, storage, h.config, h.clock),
            PurgeAttachments(attachments, h.transactor, storage, h.config.copy(attachmentPurgeBatch = 2), h.clock),
        )

    private suspend fun chat(): ChatId = h.group(amina, listOf(brian)).chat.chatId

    private suspend fun prepare(
        chatId: ChatId,
        caller: UserId = amina,
        sizeBytes: Long = SIZE,
        kind: AttachmentKind = AttachmentKind.IMAGE,
    ) = service.prepare(PrepareAttachmentCommand(caller, chatId, sizeBytes, kind))

    /** AT1 then the phone's PUT then AT2: a ready attachment. */
    private suspend fun ready(chatId: ChatId): UUID {
        val id = prepare(chatId).data().attachmentId
        storage.put(chatId, id, SIZE)
        service.complete(amina, id).data()
        return id
    }

    // ---- AT1 ----

    @Test
    fun `a member gets a 15-minute octet-stream upload and a pending row kept 30 days`() =
        runTest {
            val chatId = chat()

            val upload = prepare(chatId, kind = AttachmentKind.VOICE).data()

            assertEquals("application/octet-stream", upload.contentType)
            assertEquals(h.clock.instant().plus(Duration.ofMinutes(15)), upload.expiresAt)
            assertEquals(Duration.ofMinutes(15), storage.signedUploads.single().second)
            assertTrue(upload.uploadUrl.endsWith("attachments/$chatId/${upload.attachmentId}"))
            val row = attachments.rows.getValue(upload.attachmentId)
            assertEquals(AttachmentState.PENDING, row.state)
            assertEquals(AttachmentKind.VOICE, row.kind)
            assertEquals(amina, row.uploaderId)
            assertEquals(SIZE, row.sizeBytes)
            assertEquals(row.createdAt.plus(Duration.ofDays(30)), row.expiresAt)
        }

    @Test
    fun `someone outside the chat gets chat_not_found and nothing is signed or stored`() =
        runTest {
            val chatId = chat()

            assertEquals("messaging.chat_not_found", prepare(chatId, caller = chidi).errorCode())
            assertEquals("messaging.chat_not_found", prepare(ChatId.new()).errorCode())
            assertTrue(storage.signedUploads.isEmpty())
            assertTrue(attachments.rows.isEmpty())
        }

    @Test
    fun `sizes from 1 byte to 32 MiB are accepted and anything else is too large`() =
        runTest {
            val chatId = chat()
            val max = 32L * 1_024 * 1_024

            prepare(chatId, sizeBytes = 1).data()
            prepare(chatId, sizeBytes = max).data()
            assertEquals("messaging.attachment_too_large", prepare(chatId, sizeBytes = max + 1).errorCode())
            assertEquals("messaging.attachment_too_large", prepare(chatId, sizeBytes = 0).errorCode())
            assertEquals("messaging.attachment_too_large", prepare(chatId, sizeBytes = -5).errorCode())
            assertEquals(2, attachments.rows.size)
        }

    // ---- AT2 ----

    @Test
    fun `the uploader completes an upload of the promised size and it becomes ready`() =
        runTest {
            val chatId = chat()
            val id = prepare(chatId).data().attachmentId
            storage.put(chatId, id, SIZE)

            val completed = service.complete(amina, id).data()

            assertEquals(id, completed.attachmentId)
            assertEquals(SIZE, completed.sizeBytes)
            assertEquals(AttachmentState.READY, attachments.rows.getValue(id).state)
            assertEquals(SIZE, service.complete(amina, id).data().sizeBytes)
        }

    @Test
    fun `only the uploader can complete, others see not_found`() =
        runTest {
            val chatId = chat()
            val id = prepare(chatId).data().attachmentId
            storage.put(chatId, id, SIZE)

            assertEquals("messaging.attachment_not_found", service.complete(brian, id).errorCode())
            assertEquals("messaging.attachment_not_found", service.complete(amina, UUID.randomUUID()).errorCode())
            assertEquals(AttachmentState.PENDING, attachments.rows.getValue(id).state)
        }

    @Test
    fun `a missing object or the wrong size is incomplete and stays pending`() =
        runTest {
            val chatId = chat()
            val id = prepare(chatId).data().attachmentId

            assertEquals("messaging.attachment_incomplete", service.complete(amina, id).errorCode())
            storage.put(chatId, id, SIZE - 1)
            assertEquals("messaging.attachment_incomplete", service.complete(amina, id).errorCode())
            assertEquals(AttachmentState.PENDING, attachments.rows.getValue(id).state)

            storage.put(chatId, id, SIZE)
            service.complete(amina, id).data()
        }

    // ---- AT3 ----

    @Test
    fun `any member gets a 15-minute download link with the size`() =
        runTest {
            val chatId = chat()
            val id = ready(chatId)

            val download = service.download(brian, id).data()

            assertTrue(download.downloadUrl.endsWith("attachments/$chatId/$id"))
            assertEquals(h.clock.instant().plus(Duration.ofMinutes(15)), download.expiresAt)
            assertEquals(SIZE, download.sizeBytes)
            service.download(amina, id).data()
        }

    @Test
    fun `non-members, former members, pending and unknown attachments all look not found`() =
        runTest {
            val chatId = chat()
            val id = ready(chatId)
            val pending = prepare(chatId).data().attachmentId

            assertEquals("messaging.attachment_not_found", service.download(chidi, id).errorCode())
            assertEquals("messaging.attachment_not_found", service.download(brian, pending).errorCode())
            assertEquals("messaging.attachment_not_found", service.download(brian, UUID.randomUUID()).errorCode())

            h.leaveGroup(brian, chatId).data()
            assertEquals("messaging.attachment_not_found", service.download(brian, id).errorCode())
        }

    @Test
    fun `an expired attachment can no longer be downloaded or completed`() =
        runTest {
            val chatId = chat()
            val id = ready(chatId)
            val pending = prepare(chatId).data().attachmentId
            storage.put(chatId, pending, SIZE)

            h.clock.advance(Duration.ofDays(30))

            assertEquals("messaging.attachment_not_found", service.download(brian, id).errorCode())
            assertEquals("messaging.attachment_not_found", service.complete(amina, pending).errorCode())
        }

    // ---- purge ----

    @Test
    fun `the purge deletes expired attachments and day-old pending ones, object first, in batches`() =
        runTest {
            val chatId = chat()
            val old = List(3) { ready(chatId) }
            h.clock.advance(Duration.ofDays(29))
            val stale = prepare(chatId).data().attachmentId
            storage.put(chatId, stale, SIZE)
            val fresh = ready(chatId)
            h.clock.advance(Duration.ofHours(23))
            val recentPending = prepare(chatId).data().attachmentId

            assertEquals(0, service.purge())

            h.clock.advance(Duration.ofHours(2))

            assertEquals(4, service.purge())
            for (id in old + stale) {
                assertNull(attachments.rows[id])
                assertFalse(storage.has(chatId, id))
            }
            assertEquals(setOf(fresh, recentPending), attachments.rows.keys)
            assertTrue(storage.has(chatId, fresh))
        }

    @Test
    fun `a row whose object storage could not delete is kept for the next run`() =
        runTest {
            val chatId = chat()
            val id = ready(chatId)
            h.clock.advance(Duration.ofDays(31))
            storage.failing = true

            assertEquals(0, service.purge())
            assertTrue(attachments.rows.containsKey(id))

            storage.failing = false
            assertEquals(1, service.purge())
            assertFalse(attachments.rows.containsKey(id))
        }

    private companion object {
        const val SIZE = 123_456L
    }
}
