package com.circaso.infrastructure.impl

import com.circaso.infrastructure.api.OpsJob
import com.circaso.infrastructure.api.Scheduler
import kotlinx.coroutines.test.runTest
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The ops process's own jobs (decision 2026-11-18): each runs once as the process starts, under its lock, and one
 * that fails or is already running elsewhere never stops the server or the others.
 */
class OpsJobsTest {
    /** A scheduler whose locks in [held] are taken by another process. */
    private class Locks(private val held: Set<String> = emptySet()) : Scheduler {
        val asked = CopyOnWriteArrayList<String>()

        override fun every(
            name: String,
            interval: Duration,
            job: suspend () -> Int,
        ) = Unit

        override suspend fun <T> withLock(
            name: String,
            lease: Duration,
            block: suspend () -> T,
        ): T? {
            asked += name
            return if (name in held) null else block()
        }
    }

    private val ran = CopyOnWriteArrayList<String>()

    private fun job(name: String, fails: Boolean = false) =
        OpsJob(name, Duration.ofMinutes(10)) {
            ran += name
            if (fails) error("Circle is down")
            1
        }

    @Test
    fun `every job runs once at start, each under its own start lock`() =
        runTest {
            val locks = Locks()

            launchAtStart(locks, listOf(job("wallet.fee-wallets"), job("wallet.fee-withdrawals")))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("wallet.fee-wallets", "wallet.fee-withdrawals"), ran.sorted())
            assertEquals(listOf("wallet.fee-wallets.start", "wallet.fee-withdrawals.start"), locks.asked.sorted())
        }

    @Test
    fun `a job that fails at start doesn't stop the others, and nothing is thrown`() =
        runTest {
            launchAtStart(Locks(), listOf(job("a", fails = true), job("b")))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("a", "b"), ran.sorted())
        }

    @Test
    fun `a job another ops process is already running at start is left to it`() =
        runTest {
            launchAtStart(Locks(held = setOf("a.start")), listOf(job("a"), job("b")))
            testScheduler.advanceUntilIdle()

            assertEquals(listOf("b"), ran.toList())
        }
}
