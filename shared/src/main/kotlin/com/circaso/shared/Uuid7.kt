package com.circaso.shared

import java.security.SecureRandom
import java.time.Instant
import java.util.UUID

/**
 * UUIDv7 (RFC 9562): a 48-bit Unix millisecond timestamp, then random bits. Ids sort by creation time,
 * which keeps a B-tree index on them append-mostly and lets keyset paging order by id.
 */
object Uuid7 {
    private val random = SecureRandom()

    fun generate(now: Instant = Instant.now()): UUID {
        val millis = now.toEpochMilli()
        require(millis in 0..0xFFFF_FFFF_FFFFL) { "UUIDv7 holds a 48-bit millisecond timestamp" }
        val randA = random.nextInt(1 shl 12).toLong()
        val randB = random.nextLong()
        val msb = (millis shl 16) or (0x7L shl 12) or randA
        val lsb = (randB and 0x3FFF_FFFF_FFFF_FFFFL) or (0x2L shl 62)
        return UUID(msb, lsb)
    }

    /** When a UUIDv7 was created, or null if [uuid] is another version. */
    fun timestampOf(uuid: UUID): Instant? =
        if (uuid.version() == 7) Instant.ofEpochMilli(uuid.mostSignificantBits ushr 16) else null

    private val canonical = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** Strict parse: only the canonical 8-4-4-4-12 form, never the lenient shapes `UUID.fromString` accepts. */
    fun parse(raw: String): UUID? = if (canonical.matches(raw)) UUID.fromString(raw) else null
}
