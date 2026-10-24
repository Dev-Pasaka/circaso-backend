package com.circaso.infrastructure.impl.scheduler

import com.circaso.infrastructure.impl.TestRedis
import io.lettuce.core.ExperimentalLettuceCoroutinesApi
import io.lettuce.core.SetArgs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalLettuceCoroutinesApi::class)
class RedisSchedulerTest {
    private val redis = TestRedis.connect()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val name = "test.job-${UUID.randomUUID()}"

    @AfterTest
    fun close() {
        scope.cancel()
        redis.close()
    }

    @Test
    fun `withLock runs one holder at a time and tells the other it lost`() =
        runBlocking<Unit> {
            val a = RedisScheduler(redis, nodeId = "a")
            val b = RedisScheduler(redis, nodeId = "b")

            val results =
                listOf(
                    async {
                        a.withLock(name, Duration.ofSeconds(10)) {
                            delay(300)
                            "a ran"
                        }
                    },
                    async {
                        delay(100)
                        b.withLock(name, Duration.ofSeconds(10)) { "b ran" }
                    },
                ).awaitAll()

            assertEquals("a ran", results[0])
            assertNull(results[1], "b must not run while a holds the lock")
        }

    @Test
    fun `withLock releases the lock when the block ends or throws`() =
        runBlocking<Unit> {
            val scheduler = RedisScheduler(redis, nodeId = "a")

            assertEquals(1, scheduler.withLock(name, Duration.ofSeconds(10)) { 1 })
            assertEquals(2, scheduler.withLock(name, Duration.ofSeconds(10)) { 2 }, "released after the first run")

            runCatching { scheduler.withLock(name, Duration.ofSeconds(10)) { error("boom") } }
            assertEquals(3, scheduler.withLock(name, Duration.ofSeconds(10)) { 3 }, "released after a failure too")
        }

    @Test
    fun `a lock held by a node that died lapses and is taken over`() =
        runBlocking<Unit> {
            redis.commands.set("sch:lock:$name", "dead-node", SetArgs().nx().px(300))
            val scheduler = RedisScheduler(redis, nodeId = "a")

            assertNull(scheduler.withLock(name, Duration.ofSeconds(10)) { "too early" })
            delay(450)

            assertEquals("took over", scheduler.withLock(name, Duration.ofSeconds(10)) { "took over" })
        }

    @Test
    fun `a slow job keeps its lock by renewing it`() =
        runBlocking<Unit> {
            val a = RedisScheduler(redis, nodeId = "a")
            val b = RedisScheduler(redis, nodeId = "b")

            val slow =
                async {
                    a.withLock(name, Duration.ofMillis(600)) {
                        delay(1500)
                        "done"
                    }
                }
            delay(1000) // well past the 600 ms lease
            val intruder = b.withLock(name, Duration.ofMillis(600)) { "intruded" }

            assertNull(intruder, "the lease was renewed, so b can't take the lock")
            assertEquals("done", slow.await())
        }

    @Test
    fun `two nodes running the same job run it once per interval between them`() =
        runBlocking<Unit> {
            val runs = AtomicInteger()
            val concurrent = AtomicInteger()
            var overlapped = false
            val job: suspend () -> Int = {
                if (concurrent.incrementAndGet() > 1) overlapped = true
                runs.incrementAndGet()
                delay(50)
                concurrent.decrementAndGet()
                1
            }
            val nodeA = RedisScheduler(redis, nodeId = "a").also { it.every(name, Duration.ofSeconds(1), job) }
            val nodeB = RedisScheduler(redis, nodeId = "b").also { it.every(name, Duration.ofSeconds(1), job) }
            nodeA.start(scope)
            nodeB.start(scope)

            delay(3500)
            nodeA.close()
            nodeB.close()

            assertTrue(
                runs.get() in 2..5,
                "two nodes over 3.5 s should run a 1 s job about 3-4 times, ran ${runs.get()}",
            )
            assertTrue(!overlapped, "the job must never run on both nodes at once")
            assertNotNull(redis.commands.get("sch:lock:$name"), "the interval lock stays until it expires")
        }

    @Test
    fun `a job that throws is logged and tried again next interval`() =
        runBlocking<Unit> {
            val attempts = AtomicInteger()
            val scheduler = RedisScheduler(redis, nodeId = "a")
            scheduler.every(name, Duration.ofMillis(1000)) {
                if (attempts.incrementAndGet() == 1) error("first run fails")
                1
            }
            scheduler.start(scope)

            delay(2800)
            scheduler.close()

            assertTrue(attempts.get() >= 2, "should have retried, attempts=${attempts.get()}")
        }
}
