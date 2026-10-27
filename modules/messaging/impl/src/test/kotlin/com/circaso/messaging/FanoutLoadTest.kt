package com.circaso.messaging

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.shared.UserId
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The fan-out measurement the plan asks for (postgres-migration.md §2.6, §6): group messages to a 1,000-member group on
 * the test container, each send one transaction (claim, record, body, 999 inbox pointers in one multi-row insert). It
 * is not part of the default build; run it with
 * `MESSAGING_LOAD_TEST=1 ./gradlew :modules:messaging:impl:test --tests '*FanoutLoadTest*' --rerun -i`.
 */
class FanoutLoadTest {
    @Test
    fun `a group message to 1,000 members, timed`() =
        runBlocking<Unit> {
            assumeTrue(System.getenv("MESSAGING_LOAD_TEST") == "1", "set MESSAGING_LOAD_TEST=1 to run the measurement")
            TestPostgres.truncate("messaging")
            val h = PostgresHarness()
            val owner = UserId.new()
            val chatId = h.group(owner, List(999) { UserId.new() })
            repeat(WARM_UP) { h.sendMessage(h.groupCommand(owner, chatId)).data() }

            val sequential = timed(SENDS) { h.sendMessage(h.groupCommand(owner, chatId)).data() }
            val parallel =
                timed(1) {
                    PostgresHarness.concurrently(SENDS) { h.sendMessage(h.groupCommand(owner, chatId)).data() }
                }

            val pointers = h.count("SELECT count(*) FROM messaging.inbox")
            assertEquals((WARM_UP + 2 * SENDS) * 999, pointers)
            println(
                (
                    "FANOUT 1,000 members: one send %.1f ms (mean of %d, one after another); " +
                        "%d sends at once %.1f ms in all, %.1f sends/s"
                ).format(
                    sequential / SENDS,
                    SENDS,
                    SENDS,
                    parallel,
                    SENDS * 1_000.0 / parallel,
                ),
            )
        }

    private suspend fun timed(times: Int, block: suspend () -> Unit): Double {
        val started = System.nanoTime()
        repeat(times) { block() }
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - started) / 1_000.0
    }

    private companion object {
        const val WARM_UP = 5
        const val SENDS = 50
    }
}
