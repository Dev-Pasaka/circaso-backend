package com.circaso.messaging

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.data.repositories.AttachmentRepositoryImpl
import com.circaso.messaging.domain.models.Attachment
import com.circaso.messaging.domain.models.AttachmentKind
import com.circaso.messaging.domain.models.AttachmentState
import com.circaso.shared.UserId
import com.circaso.shared.Uuid7
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `attachments` on PostgreSQL, migrated by V3: the round trip, the conditional `READY`, and what the purge picks. */
class AttachmentDataTest {
    private val repository = AttachmentRepositoryImpl(PostgresHarness().database)
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
}
