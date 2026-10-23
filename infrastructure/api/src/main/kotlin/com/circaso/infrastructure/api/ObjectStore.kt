package com.circaso.infrastructure.api

import com.circaso.shared.Resource
import java.time.Duration

/**
 * Files (Cloudflare R2, S3 compatible), under a bucket prefix per module, in a private bucket. Uploads and downloads go
 * straight between the phone and R2 through pre-signed URLs, so the backend never streams file bytes; it only checks
 * the object afterwards. Nothing in the bucket has a public address.
 */
interface ObjectStore {
    /** A URL the phone can PUT [contentType] bytes to until it expires. [key] is relative to the module's prefix. */
    suspend fun presignUpload(key: String, contentType: String, expiresIn: Duration): Resource<String>

    /** What was uploaded at [key], or null if nothing is there. */
    suspend fun describe(key: String): StoredObject?

    suspend fun delete(key: String)

    /** A URL anyone holding it can GET [key] from until it expires. Hand it to a signed-in user, never store it. */
    suspend fun presignDownload(key: String, expiresIn: Duration): Resource<String>
}

data class StoredObject(val sizeBytes: Long, val contentType: String)

/** Hands each module its own [ObjectStore]. [prefix] is the module's bucket prefix (`users`). */
interface   ObjectStores {
    fun scope(prefix: String): ObjectStore
}
