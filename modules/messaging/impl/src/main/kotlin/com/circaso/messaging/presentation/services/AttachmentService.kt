package com.circaso.messaging.presentation.services

import com.circaso.messaging.domain.models.Attachment
import com.circaso.messaging.domain.models.AttachmentDownload
import com.circaso.messaging.domain.models.AttachmentUpload
import com.circaso.messaging.domain.models.PrepareAttachmentCommand
import com.circaso.messaging.domain.usecases.CompleteAttachmentUpload
import com.circaso.messaging.domain.usecases.GetAttachmentDownload
import com.circaso.messaging.domain.usecases.PrepareAttachmentUpload
import com.circaso.messaging.domain.usecases.PurgeAttachments
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.util.UUID

/** Encrypted chat attachments, AT1 to AT3, and the hourly purge job. */
class AttachmentService(
    private val prepareAttachmentUpload: PrepareAttachmentUpload,
    private val completeAttachmentUpload: CompleteAttachmentUpload,
    private val getAttachmentDownload: GetAttachmentDownload,
    private val purgeAttachments: PurgeAttachments,
) {
    suspend fun prepare(command: PrepareAttachmentCommand): Resource<AttachmentUpload> =
        prepareAttachmentUpload(command)

    suspend fun complete(caller: UserId, attachmentId: UUID): Resource<Attachment> =
        completeAttachmentUpload(caller, attachmentId)

    suspend fun download(caller: UserId, attachmentId: UUID): Resource<AttachmentDownload> =
        getAttachmentDownload(caller, attachmentId)

    suspend fun purge(): Int = purgeAttachments()
}
