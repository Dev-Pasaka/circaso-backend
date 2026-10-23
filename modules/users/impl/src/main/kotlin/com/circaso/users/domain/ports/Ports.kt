package com.circaso.users.domain.ports

import com.circaso.shared.PhoneNumber
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import java.time.Duration
import java.util.UUID

/** HMAC-SHA256 of an E.164 number under the phone-HMAC key shared with identity, so waiting contacts match its events. */
interface PhoneHasher {
    fun hmac(phone: PhoneNumber): String
}

/** Cached answers to "can A reach B?" (`usr:reach:`); a target's entries are invalidated all at once. */
interface ReachCache {
    suspend fun get(from: UserId, to: UserId): Boolean?
    suspend fun put(from: UserId, to: UserId, reachable: Boolean)

    /** Forgets every cached decision towards [target]. */
    suspend fun invalidateTarget(target: UserId)
}

interface UsersCache {
    /** Counts a handle lookup; returns the count in the current hour. */
    suspend fun countHandleLookup(userId: UserId): Long
    suspend fun handleLookupRetryAfter(userId: UserId): Duration?
    suspend fun recordSyncBatch(userId: UserId, syncId: UUID, final: Boolean)
}

/** Profile pictures. */
interface AvatarStorage {
    suspend fun prepareUpload(userId: UserId, contentType: String, expiresIn: Duration): Resource<UploadedKey>
    suspend fun describe(key: String): StoredAvatar?
    suspend fun delete(key: String)
    suspend fun urlOf(key: String): String?
}

data class UploadedKey(val uploadUrl: String, val objectKey: String)

data class StoredAvatar(val sizeBytes: Long, val contentType: String)

/** Sends a realtime frame to one user and keeps it for replay. Failures never fail the operation that caused them. */
interface RealtimeFrames {
    suspend fun send(userId: UserId, type: String, data: Map<String, String>)
}

/** The frames `users` has sent, kept for 7 days so a phone that reconnects can catch up. */
interface RealtimeLog {
    /** Frames after [seq], oldest first. */
    suspend fun since(userId: UserId, seq: Long, limit: Int): List<LoggedFrame>

    /** The latest `seq` handed out for this user, or null if none is remembered. */
    suspend fun latest(userId: UserId): Long?
}

data class LoggedFrame(val seq: Long, val json: String)
