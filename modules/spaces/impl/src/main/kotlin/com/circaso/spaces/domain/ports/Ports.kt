package com.circaso.spaces.domain.ports

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.spaces.api.SpaceFrame
import java.time.Duration

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

/** Sends a frame to each person named, at their own next `seq`, kept 7 days for replay. */
interface FrameSender {
    suspend fun send(userIds: Collection<UserId>, frame: SpaceFrame)
}

/** The frames `spaces` has kept, so a phone that reconnects can catch up. */
interface RealtimeLog {
    suspend fun since(
        userId: UserId,
        seq: Long,
        limit: Int,
    ): List<LoggedFrame>

    suspend fun latest(userId: UserId): Long?
}

data class LoggedFrame(val seq: Long, val json: String)

/**
 * Space photos, covers and post files in object storage under spaces' prefix (`media/{mediaId}`), as uploaded: space
 * content isn't end to end encrypted. Bytes go straight between the phone and storage through pre-signed URLs.
 */
interface MediaStorage {
    suspend fun prepareUpload(
        key: String,
        contentType: String,
        expiresIn: Duration,
    ): Resource<String>

    /** The size of what was uploaded, or null when nothing is there. */
    suspend fun sizeOf(key: String): Long?

    /** A download URL, or null when storage can't make one now (the app shows the tile without it). */
    suspend fun downloadUrl(key: String, expiresIn: Duration): String?

    suspend fun delete(key: String)
}
