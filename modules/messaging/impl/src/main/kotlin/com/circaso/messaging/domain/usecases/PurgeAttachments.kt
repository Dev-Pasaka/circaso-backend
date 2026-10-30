package com.circaso.messaging.domain.usecases

import com.circaso.messaging.domain.ports.AttachmentStorage
import com.circaso.messaging.domain.ports.Transactor
import com.circaso.messaging.domain.repositories.AttachmentRepository
import com.circaso.messaging.domain.support.MessagingConfig
import java.time.Clock

/**
 * `messaging.purge-attachments`, hourly on the leader: deletes attachments 30 days after AT1, and those still pending
 * a day after it. The storage object goes first and the row after, so a row is never lost while its blob stays; an
 * object storage could not delete keeps its row for the next run. Returns how many attachments were deleted.
 */
class PurgeAttachments(
    private val attachments: AttachmentRepository,
    private val transactor: Transactor,
    private val storage: AttachmentStorage,
    private val config: MessagingConfig,
    private val clock: Clock,
) {
    suspend operator fun invoke(): Int {
        val now = clock.instant()
        val pendingBefore = now.minus(config.pendingAttachmentTtl)
        var deleted = 0
        while (true) {
            val batch = attachments.purgeable(now, pendingBefore, config.attachmentPurgeBatch)
            val gone = batch.filter { storage.delete(it.chatId, it.attachmentId) }
            for (attachment in gone) {
                if (transactor.run("attachments.purge") { attachments.delete(attachment.attachmentId) }) deleted++
            }
            // A short batch is the last; a batch storage refused entirely would only come back, so stop until later.
            if (batch.size < config.attachmentPurgeBatch || gone.isEmpty()) return deleted
        }
    }
}
