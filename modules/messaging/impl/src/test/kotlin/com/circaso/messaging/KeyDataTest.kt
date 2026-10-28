package com.circaso.messaging

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.messaging.PostgresHarness.Companion.RACE_RUNS
import com.circaso.messaging.PostgresHarness.Companion.concurrently
import com.circaso.messaging.PostgresHarness.Companion.proof
import com.circaso.messaging.domain.models.AddOneTimeKeysCommand
import com.circaso.messaging.domain.models.KeyBundle
import com.circaso.messaging.domain.models.KyberPreKey
import com.circaso.messaging.domain.models.OneTimePreKey
import com.circaso.messaging.domain.models.SignedPreKey
import com.circaso.shared.DeviceId
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The key directory on PostgreSQL: a `PUT /keys` that replaces everything, top-ups decided under the bundle's lock
 * and refused whole, one-time prekeys taken with `DELETE … RETURNING` over `SKIP LOCKED`, and a replaced phone's keys
 * deleted by the event's time.
 */
class KeyDataTest {
    private val h = PostgresHarness()
    private val keys = h.keys
    private val now = Instant.now().truncatedTo(ChronoUnit.MICROS)

    @BeforeTest
    fun clean() = TestPostgres.truncate("messaging")

    private fun bundle(userId: UserId, deviceId: DeviceId) =
        KeyBundle(
            userId,
            deviceId,
            "identity",
            SignedPreKey(7, "signed", "signature"),
            KyberPreKey(3, "kyber", "kyber-sig"),
            now,
        )

    private fun prekeys(ids: IntRange) = ids.map { OneTimePreKey(it, "key-$it") }

    /** `POST /keys/one-time` through `AddOneTimeKeys` itself, with a proof that passes. */
    private suspend fun topUp(
        userId: UserId,
        deviceId: DeviceId,
        ids: IntRange,
    ): Resource<Int> = h.addOneTimeKeys(AddOneTimeKeysCommand(userId, deviceId, prekeys(ids), proof(h.clock)))

    @Test
    fun `a PUT keys replaces the bundle and every one-time prekey`() =
        runBlocking<Unit> {
            val (userId, phone) = UserId.new() to DeviceId.new()
            keys.publish(bundle(userId, phone), prekeys(1..5), now)

            keys.publish(bundle(userId, phone).copy(identityKey = "rotated"), prekeys(10..12), now)

            assertEquals("rotated", keys.bundle(userId)!!.identityKey)
            assertEquals((10..12).toSet(), keys.oneTimeKeyIds(userId, phone))
        }

    @Test
    fun `concurrent fetches never get the same prekey, and each one taken is gone (50 runs)`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) { run ->
                val (userId, phone) = UserId.new() to DeviceId.new()
                keys.publish(bundle(userId, phone), prekeys(1..40), now)

                val taken = concurrently(50) { keys.claimOneTimeKey(userId, phone) }

                val ids = taken.mapNotNull { it?.keyId }
                assertEquals(40, ids.size, "every key handed out once, run $run")
                assertEquals((1..40).toSet(), ids.toSet())
                assertEquals(10, taken.count { it == null }, "the rest got a bundle without one")
                assertTrue(keys.oneTimeKeyIds(userId, phone).isEmpty())
            }
        }

    @Test
    fun `a fetch takes only keys of the phone that published the bundle`() =
        runBlocking<Unit> {
            val (userId, phone) = UserId.new() to DeviceId.new()
            keys.publish(bundle(userId, phone), prekeys(1..2), now)

            assertNull(keys.claimOneTimeKey(userId, DeviceId.new()))
            assertEquals(1, keys.claimOneTimeKey(userId, phone)!!.keyId, "the lowest first")
            assertEquals(setOf(2), keys.oneTimeKeyIds(userId, phone), "one fetch takes one key")
        }

    @Test
    fun `a refused top-up keeps nothing of its own`() =
        runBlocking<Unit> {
            val (userId, phone) = UserId.new() to DeviceId.new()
            keys.publish(bundle(userId, phone), prekeys(1..10), now)

            assertEquals("messaging.too_many_prekeys", topUp(userId, phone, 11..101).errorCode())
            assertEquals("messaging.prekey_id_exists", topUp(userId, phone, 10..12).errorCode())

            assertEquals((1..10).toSet(), keys.oneTimeKeyIds(userId, phone))
        }

    @Test
    fun `of two top-ups of 30 onto 60 stored, exactly one gets in and 90 are kept (50 runs)`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) { run ->
                val (userId, phone) = UserId.new() to DeviceId.new()
                keys.publish(bundle(userId, phone), prekeys(1..60), now)

                val results = concurrently(2) { i -> topUp(userId, phone, if (i == 0) 61..90 else 91..120) }

                assertEquals(listOf(90), results.filterIsInstance<Resource.Success<Int>>().map { it.data }, "run $run")
                assertEquals(
                    "messaging.too_many_prekeys",
                    results
                        .filterIsInstance<Resource.Error>()
                        .single()
                        .error.code,
                )
                assertEquals(90, keys.oneTimeKeyIds(userId, phone).size, "the cap held, run $run")
            }
        }

    @Test
    fun `of identical top-ups racing, exactly one succeeds and nothing of the others stays (50 runs)`() =
        runBlocking<Unit> {
            repeat(RACE_RUNS) { run ->
                val (userId, phone) = UserId.new() to DeviceId.new()
                keys.publish(bundle(userId, phone), prekeys(1..5), now)

                val results = concurrently(6) { topUp(userId, phone, 6..10) }

                assertEquals(listOf(10), results.filterIsInstance<Resource.Success<Int>>().map { it.data }, "run $run")
                assertTrue(
                    results.filterIsInstance<Resource.Error>().all {
                        it.error.code ==
                            "messaging.prekey_id_exists"
                    },
                )
                assertEquals((1..10).toSet(), keys.oneTimeKeyIds(userId, phone))
            }
        }

    @Test
    fun `a replaced phone's bundle and its keys from before the event go, what came after stays`() =
        runBlocking<Unit> {
            val (userId, oldPhone, newPhone) = Triple(UserId.new(), DeviceId.new(), DeviceId.new())
            keys.publish(bundle(userId, oldPhone), prekeys(1..3), now.minusSeconds(60))
            keys.addOneTimeKeys(userId, oldPhone, prekeys(4..4), now.plusSeconds(60))

            assertTrue(keys.removeBundle(userId, oldPhone, now))

            assertNull(keys.bundle(userId))
            assertEquals(setOf(4), keys.oneTimeKeyIds(userId, oldPhone), "a key written after the event is kept")
            keys.publish(bundle(userId, newPhone), prekeys(1..2), now)
            assertFalse(keys.removeBundle(userId, oldPhone, now.plusSeconds(120)), "the new phone's bundle stays")
            assertEquals(newPhone, keys.bundle(userId)!!.deviceId)
            assertEquals(setOf(1, 2), keys.oneTimeKeyIds(userId, newPhone))
        }
}
