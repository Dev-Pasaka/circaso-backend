package com.circaso.status.presentation.services

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.domain.models.MediaDownload
import com.circaso.status.domain.models.MediaUpload
import com.circaso.status.domain.models.MomentMedia
import com.circaso.status.domain.models.PrepareMediaCommand
import com.circaso.status.domain.usecases.CompleteMomentMedia
import com.circaso.status.domain.usecases.GetMomentMediaDownload
import com.circaso.status.domain.usecases.PrepareMomentMedia
import com.circaso.status.domain.usecases.PurgeMomentMedia
import java.util.UUID

/** Moment files, ST1, ST2 and ST8, and the file half of the sweep. */
class MomentMediaService(
    private val prepareMomentMedia: PrepareMomentMedia,
    private val completeMomentMedia: CompleteMomentMedia,
    private val getMomentMediaDownload: GetMomentMediaDownload,
    private val purgeMomentMedia: PurgeMomentMedia,
) {
    suspend fun prepare(command: PrepareMediaCommand): Resource<MediaUpload> = prepareMomentMedia(command)

    suspend fun complete(caller: UserId, mediaId: UUID): Resource<MomentMedia> = completeMomentMedia(caller, mediaId)

    suspend fun download(caller: UserId, mediaId: UUID): Resource<MediaDownload> =
        getMomentMediaDownload(caller, mediaId)

    suspend fun purge(): Int = purgeMomentMedia()
}
