package com.circaso.infrastructure.impl.logging

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.circaso.infrastructure.api.WarmingUp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The warm-up's own slow database and Redis calls are DEBUG, not WARN (deploy log 2026-11-05): `PostgresDatabase`
 * and `LettuceRedisScope` both write their slow line through [slow], with [warmingUp] read where the call starts.
 */
class SlowLogTest {
    private val captured = ListAppender<ILoggingEvent>().apply { start() }
    private val logger =
        (LoggerFactory.getLogger("com.circaso.test.SlowLog") as Logger).apply {
            level = Level.DEBUG
            addAppender(captured)
        }

    @AfterTest
    fun release() {
        logger.detachAppender(captured)
    }

    private suspend fun slowCall() =
        logger.slow(warmingUp(), "slow transaction: in the database", kv("durationMs", 470))

    @Test
    fun `a slow call made by the warm-up is DEBUG, and the same call from a request is WARN`() =
        runBlocking {
            withContext(WarmingUp) { slowCall() }
            slowCall()

            assertEquals(listOf(Level.DEBUG, Level.WARN), captured.list.map { it.level })
            assertEquals(captured.list[0].formattedMessage, captured.list[1].formattedMessage, "the same line")
        }

    @Test
    fun `a call the warm-up starts in a child coroutine is still the warm-up's`() =
        runBlocking {
            withContext(WarmingUp) {
                coroutineScope { launch { slowCall() } }
            }
            assertEquals(listOf(Level.DEBUG), captured.list.map { it.level })
        }
}
