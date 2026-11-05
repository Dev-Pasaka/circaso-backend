package com.circaso.messaging

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.data.repositories.AttachmentRepositoryImpl
import com.circaso.messaging.domain.models.Attachment
import com.circaso.messaging.domain.models.AttachmentKind
import com.circaso.messaging.domain.models.AttachmentState
import com.circaso.messaging.domain.models.AvatarChange
import com.circaso.messaging.domain.models.UpdateGroupCommand
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `attachments` on PostgreSQL, migrated by V3 and V6: the round trip, the conditional `READY`, what the purge picks,
 * and a group photo kept without an expiry (M8) and released again.
 */
class AttachmentDataTest {
    private val harness = PostgresHarness()
    private val repository = AttachmentRepositoryImpl(harness.database)
    private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeTest
    fun clean() = TestPostgres.truncate("messaging")

    private fun attachment(createdAt: Instant = now, state: AttachmentState = AttachmentState.PENDING) =
        Attachment(
            Uuid7.generate(),
            ChatId.new(),
            UserId.new(),
            AttachmentKind.VIDEO,
            4_096,
            state,
            createdAt,
            createdAt.plus(Duration.ofDays(30)),
        )

    @Test
    fun `an attachment reads back as written and turns ready once`() =
        runBlocking<Unit> {
            val row = attachment()
            repository.insert(row)

            assertEquals(row, repository.find(row.attachmentId))
            assertTrue(repository.markReady(row.attachmentId))
            assertFalse(repository.markReady(row.attachmentId))
            assertEquals(AttachmentState.READY, repository.find(row.attachmentId)?.state)
        }

    @Test
    fun `the purge picks expired rows and day-old pending ones, oldest first, and deletes them`() =
        runBlocking<Unit> {
            val expired = attachment(now.minus(Duration.ofDays(31)), AttachmentState.READY)
            val stalePending = attachment(now.minus(Duration.ofDays(2)))
            val ready = attachment(now.minus(Duration.ofDays(2)), AttachmentState.READY)
            val freshPending = attachment(now.minus(Duration.ofHours(2)))
            for (row in listOf(expired, stalePending, ready, freshPending)) repository.insert(row)

            val picked = repository.purgeable(now, now.minus(Duration.ofDays(1)), 10)

            assertEquals(listOf(expired.attachmentId, stalePending.attachmentId), picked.map { it.attachmentId })
            assertEquals(1, repository.purgeable(now, now.minus(Duration.ofDays(1)), 1).size)
            assertTrue(repository.delete(expired.attachmentId))
            assertFalse(repository.delete(expired.attachmentId))
            assertNull(repository.find(expired.attachmentId))
        }

    @Test
    fun `a ready attachment kept as a group photo has no expiry and the purge never picks it`() =
        runBlocking<Unit> {
            val photo = attachment(now.minus(Duration.ofDays(2)), AttachmentState.READY)
            val pending = attachment(now.minus(Duration.ofHours(1)))
            repository.insert(photo)
            repository.insert(pending)

            assertTrue(repository.keepAsGroupPhoto(photo.attachmentId, now))
            assertTrue(repository.keepAsGroupPhoto(photo.attachmentId, now), "keeping it again is harmless")
            assertFalse(repository.keepAsGroupPhoto(pending.attachmentId, now), "only a completed upload")

            assertNull(repository.find(photo.attachmentId)?.expiresAt)
            assertEquals(
                emptyList(),
                repository.purgeable(now.plus(Duration.ofDays(400)), now, 10).map { it.attachmentId },
            )
        }

    @Test
    fun `an expired attachment can't become a group photo`() =
        runBlocking<Unit> {
            val expired = attachment(now.minus(Duration.ofDays(31)), AttachmentState.READY)
            repository.insert(expired)

            assertFalse(repository.keepAsGroupPhoto(expired.attachmentId, now))
            assertEquals(expired.expiresAt, repository.find(expired.attachmentId)?.expiresAt)
        }

    @Test
    fun `a released group photo expires once, at the time it was given`() =
        runBlocking<Unit> {
            val photo = attachment(now.minus(Duration.ofDays(2)), AttachmentState.READY)
            repository.insert(photo)
            assertTrue(repository.keepAsGroupPhoto(photo.attachmentId, now))
            val purgeAt = now.plus(Duration.ofHours(24))

            assertTrue(repository.releaseGroupPhoto(photo.attachmentId, purgeAt))
            assertFalse(repository.releaseGroupPhoto(photo.attachmentId, now), "a retry never moves the deadline")

            assertEquals(purgeAt, repository.find(photo.attachmentId)?.expiresAt)
            assertEquals(emptyList(), repository.purgeable(now, now, 10).map { it.attachmentId })
            assertEquals(listOf(photo.attachmentId), repository.purgeable(purgeAt, now, 10).map { it.attachmentId })
        }

    @Test
    fun `M8 on PostgreSQL stores the photo on the chat and releases the one it replaces`() =
        runBlocking<Unit> {
            val owner = UserId.new()
            val chatId = harness.group(owner, emptyList())
            val version = harness.chats.chat(chatId)!!.version
            val first = attachment(now, AttachmentState.READY).copy(chatId = chatId, kind = AttachmentKind.IMAGE)
            val second = attachment(now, AttachmentState.READY).copy(chatId = chatId, kind = AttachmentKind.IMAGE)
            repository.insert(first)
            repository.insert(second)

            val set = harness.updateGroup(photoChange(owner, chatId, version, first.attachmentId)).data()
            val replaced = harness.updateGroup(photoChange(owner, chatId, set.chat.version, second.attachmentId)).data()

            assertEquals(first.attachmentId, set.chat.avatarMediaId)
            assertEquals(second.attachmentId, replaced.chat.avatarMediaId)
            assertEquals(second.attachmentId, harness.chats.chat(chatId)?.avatarMediaId)
            assertNull(repository.find(second.attachmentId)?.expiresAt)
            assertNotNull(repository.find(first.attachmentId)?.expiresAt)
        }

    private fun photoChange(
        caller: UserId,
        chatId: ChatId,
        version: Long,
        photo: UUID,
    ) = UpdateGroupCommand(
        caller,
        chatId,
        null,
        null,
        version,
        AvatarChange.Set(photo),
    )
}
