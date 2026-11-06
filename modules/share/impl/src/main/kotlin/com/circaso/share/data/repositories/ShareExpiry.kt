package com.circaso.share.data.repositories

import com.circaso.infrastructure.api.Database

/** The database half of `share.purge-expired`: deletes links 30 days after they ended or were turned off. */
class ShareExpiry(private val database: Database) {
    suspend fun run(): Int = database.purgeExpired("links", CHUNK)

    private companion object {
        const val CHUNK = 1_000
    }
}
