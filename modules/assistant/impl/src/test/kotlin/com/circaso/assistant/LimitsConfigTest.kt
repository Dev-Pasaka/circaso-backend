package com.circaso.assistant

import com.circaso.assistant.di.assistantConfig
import com.circaso.assistant.domain.models.Plan
import com.circaso.assistant.domain.models.TurnCommand
import com.circaso.assistant.domain.support.AssistantConfig
import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Environment
import com.circaso.shared.Resource
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Asha's limits from the environment (decision 2026-10-31), and the testing switch that lifts them. */
class LimitsConfigTest {
    private class MapConfig(private val values: Map<String, String>, private val prefix: String = "") : Config {
        override val environment = Environment.LOCAL

        override fun string(key: String): String? = values[prefix + key]

        override fun section(prefix: String): Config = MapConfig(values, "${this.prefix}$prefix.")
    }

    private fun configOf(vararg values: Pair<String, String>) =
        assistantConfig(MapConfig(values.toMap()).section("assistant"))

    @Test
    fun `unset, the defaults hold and the switch is off`() {
        val config = configOf()

        assertFalse(config.isUnlimited)
        assertEquals(AssistantConfig().free, config.limitsOf(Plan.FREE))
        assertEquals(AssistantConfig().plus, config.limitsOf(Plan.PLUS))
    }

    @Test
    fun `the switch lifts every limit for every plan`() {
        val config = configOf("assistant.limits-unlimited" to "true")

        assertTrue(config.isUnlimited)
        listOf(Plan.FREE, Plan.PLUS).forEach { plan ->
            val limits = config.limitsOf(plan)
            assertEquals(AssistantConfig.UNLIMITED, limits)
            assertTrue(limits.textTurnsPerDay >= 1_000_000)
            assertTrue(limits.voiceSecondsPerDay >= 1_000_000)
            assertTrue(limits.contextMessages > AssistantConfig().plus.contextMessages)
        }
    }

    @Test
    fun `each plan's numbers can be set on their own`() {
        val config =
            configOf(
                "assistant.free.text-turns-per-day" to "50",
                "assistant.plus.voice-seconds-per-day" to "3600",
                "assistant.max-tool-steps" to "12",
            )

        assertEquals(50, config.limitsOf(Plan.FREE).textTurnsPerDay)
        assertEquals(3_600, config.limitsOf(Plan.PLUS).voiceSecondsPerDay)
        assertEquals(12, config.maxToolSteps)
    }

    @Test
    fun `with the switch on, a free user is never refused for the day's turns`() =
        runBlocking<Unit> {
            val h = Harness(config = configOf("assistant.limits-unlimited" to "true"))
            repeat(25) { h.model.say("Sawa.") }

            val results = (1..25).map { h.start(TurnCommand(h.owner, "Habari $it", null, true, null, "r")) }

            assertTrue(results.all { it is Resource.Success }, "${results.last()}")
        }
}
