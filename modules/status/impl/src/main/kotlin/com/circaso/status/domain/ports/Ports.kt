package com.circaso.status.domain.ports

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.status.api.StatusFrame
import java.time.Duration
import java.util.UUID

/**
 * One operation, one transaction (working rules → Postgres and Exposed): the reads a write depends on, the write
 * and its claims run inside. Repositories called inside join the open transaction; frames and storage come after.
 */
interface Transactor {
    /** Runs [block] in one transaction; [operation] is a string literal naming it in the log line. */
    suspend fun <T> run(operation: String, block: suspend () -> T): T

    /** Like [run]; a block that answers `Resource.Error` rolls everything it wrote back. */
    suspend fun <T> attempt(operation: String, block: suspend () -> Resource<T>): Resource<T>
}

/**
 * Moment files in object storage under status' prefix (`media/{posterId}/{mediaId}`), always ciphertext. Bytes go
 * straight between the phone and storage through pre-signed URLs; the backend only checks the size afterwards.
 */
interface MediaStorage {
    suspend fun prepareUpload(
        posterId: UserId,
        mediaId: UUID,
        expiresIn: Duration,
    ): Resource<String>

    /** The size of what was uploaded, or null when nothing is there. */
    suspend fun sizeOf(posterId: UserId, mediaId: UUID): Long?

    suspend fun prepareDownload(
        posterId: UserId,
        mediaId: UUID,
        expiresIn: Duration,
    ): Resource<String>

    /** True once the object is gone (or was never there); false when storage could not be reached. */
    suspend fun delete(posterId: UserId, mediaId: UUID): Boolean
}

/** Sends a frame to each person named, at their own next `seq`, and keeps it 7 days for replay. Never pushed. */
interface FrameSender {
    suspend fun send(userIds: Collection<UserId>, frame: StatusFrame)
}

/** The frames `status` has kept, so a phone that reconnects can catch up. */
interface RealtimeLog {
    /** Frames after [seq], oldest first. */
    suspend fun since(
        userId: UserId,
        seq: Long,
        limit: Int,
    ): List<LoggedFrame>

    /** The latest `seq` handed out for this user, or null if none is remembered. */
    suspend fun latest(userId: UserId): Long?
}

data class LoggedFrame(val seq: Long, val json: String)
