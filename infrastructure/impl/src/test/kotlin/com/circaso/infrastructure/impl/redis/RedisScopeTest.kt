package com.circaso.infrastructure.impl.redis

import com.circaso.infrastructure.impl.TestRedis
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(io.lettuce.core.ExperimentalLettuceCoroutinesApi::class)
class RedisScopeTest {

    private val redis = TestRedis.connect()
    private val scopes = LettuceRedisScopes(redis)
    private val idn = scopes.scope("idn")
    private val id = UUID.randomUUID().toString()

    @AfterTest
    fun close() = redis.close()

    @Test
    fun `a value is stored under the module prefix and expires`() = runBlocking {
        idn.set("otp:$id", "123456", Duration.ofMillis(400))

        assertEquals("123456", idn.get("otp:$id"))
        assertEquals("123456", redis.commands.get("idn:otp:$id"), "the key carries the module prefix")
        assertTrue(idn.ttl("otp:$id")!! <= Duration.ofMillis(400))

        delay(600)
        assertNull(idn.get("otp:$id"))
        assertNull(idn.ttl("otp:$id"))
    }

    @Test
    fun `two modules with different prefixes never see each other's keys`() = runBlocking {
        val usr = scopes.scope("usr")
        idn.set("seq:$id", "1", Duration.ofSeconds(30))

        assertNull(usr.get("seq:$id"))
        assertFalse(usr.exists("seq:$id"))
    }

    @Test
    fun `setIfAbsent claims a key once`() = runBlocking {
        assertTrue(idn.setIfAbsent("claim:$id", "first", Duration.ofSeconds(30)))
        assertFalse(idn.setIfAbsent("claim:$id", "second", Duration.ofSeconds(30)))

        assertEquals("first", idn.get("claim:$id"))
    }

    @Test
    fun `getAndDelete hands a single-use value to one caller only`() = runBlocking {
        idn.set("nonce:$id", "n", Duration.ofSeconds(30))

        assertEquals("n", idn.getAndDelete("nonce:$id"))
        assertNull(idn.getAndDelete("nonce:$id"))
    }

    @Test
    fun `delete says whether anything was there`() = runBlocking {
        idn.set("k:$id", "v", Duration.ofSeconds(30))

        assertTrue(idn.delete("k:$id"))
        assertFalse(idn.delete("k:$id"))
    }

    @Test
    fun `a counter counts and its expiry is set once, not extended`() = runBlocking {
        assertEquals(1, idn.increment("rate:$id", Duration.ofSeconds(30)))
        val firstTtl = idn.ttl("rate:$id")!!
        delay(150)
        assertEquals(2, idn.increment("rate:$id", Duration.ofSeconds(30)))
        assertEquals(3, idn.increment("rate:$id", Duration.ofSeconds(30)))

        assertTrue(idn.ttl("rate:$id")!! < firstTtl, "later increments must not push the expiry out")
    }

    @Test
    fun `a counter always has an expiry`() = runBlocking {
        idn.increment("rate:$id", Duration.ofSeconds(30))

        assertTrue(redis.commands.pttl("idn:rate:$id")!! > 0)
    }

    @Test
    fun `a hash is written with its expiry and fields can be counted up`() = runBlocking {
        idn.hashSet("otp:$id", mapOf("hmac" to "ab", "attempts" to "0"), Duration.ofSeconds(30))

        assertEquals(mapOf("hmac" to "ab", "attempts" to "0"), idn.hashGetAll("otp:$id"))
        assertEquals(1, idn.hashIncrement("otp:$id", "attempts"))
        assertEquals(2, idn.hashIncrement("otp:$id", "attempts"))
        assertTrue(redis.commands.pttl("idn:otp:$id")!! > 0)
    }

    @Test
    fun `counting up a hash that expired does not bring back a key with no expiry`() = runBlocking {
        assertNull(idn.hashIncrement("otp:gone-$id", "attempts"))

        assertFalse(idn.exists("otp:gone-$id"))
    }

    @Test
    fun `a prefix is two to eight lowercase letters`() {
        for (bad in listOf("", "a", "Idn", "id-n", "toolongprefix", "idn:")) {
            assertFailsWith<IllegalArgumentException>("prefix '$bad'") { scopes.scope(bad) }
        }
        assertNotNull(scopes.scope("idn"))
    }

    @Test
    fun `a message published on a channel reaches a subscriber of the same prefix only`() = runBlocking {
        val usr = scopes.scope("usr")
        val channel = "node:$id"
        val received = kotlinx.coroutines.CompletableDeferred<String>()
        val job = launch(kotlinx.coroutines.Dispatchers.IO) {
            idn.subscribe(channel).collect { received.complete(it) }
        }
        delay(300)

        usr.publish(channel, "wrong module")
        idn.publish(channel, "hello")

        assertEquals("hello", kotlinx.coroutines.withTimeout(3000) { received.await() })
        job.cancel()
    }
}
