package com.circaso.ramp.domain.support

import com.circaso.ramp.domain.models.Direction
import com.circaso.ramp.domain.models.EngineSettings
import com.circaso.ramp.domain.models.EngineSettingsVersion
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.repositories.EngineSettingsRepository
import com.circaso.shared.Network
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * The engine's settings in force, per network and direction (decision 2026-11-23), read the way the ramp's own
 * settings are ([SettingsBook]): from `ramp.engine_settings` at most once every [maxAge] per process, so a change staff
 * make applies everywhere within seconds with no restart; a network and direction with no row yet are seeded once
 * from the built-in defaults; when the table can't be read, what was read last stays in force.
 */
class EngineBook(
    private val repository: EngineSettingsRepository,
    private val alerts: RampAlerts,
    private val clock: Clock,
    private val maxAge: Duration = SettingsBook.MAX_AGE,
) {
    private class Loaded(val rows: Map<Pair<Network, Direction>, EngineSettingsVersion>, val at: Instant)

    @Volatile
    private var loaded: Loaded? = null

    /** The settings of [network] and [direction] now (at most [maxAge] old), with their version. */
    suspend fun of(network: Network, direction: Direction): EngineSettingsVersion =
        fresh()[network to direction] ?: seedOf(network, direction).copy(version = 0)

    /** Makes the next read go to the table (after a change made in this process). */
    fun invalidate() {
        loaded = loaded?.let { Loaded(it.rows, Instant.EPOCH) }
    }

    private suspend fun fresh(): Map<Pair<Network, Direction>, EngineSettingsVersion> {
        val current = loaded
        if (current != null && clock.instant().isBefore(current.at.plus(maxAge))) return current.rows
        return try {
            read().also { loaded = Loaded(it, clock.instant()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A database hiccup must not change how orders are routed: the last read stays in force.
            alerts.stuck("engine settings", e::class.simpleName.orEmpty())
            current?.rows ?: emptyMap()
        }
    }

    private suspend fun read(): Map<Pair<Network, Direction>, EngineSettingsVersion> {
        val stored = repository.load()
        val missing = ALL.filter { it !in stored }
        if (missing.isEmpty()) return stored
        missing.forEach { (network, direction) -> repository.seed(seedOf(network, direction)) }
        return repository.load()
    }

    private fun seedOf(network: Network, direction: Direction) =
        EngineSettingsVersion(
            network = network,
            direction = direction,
            version = FIRST_VERSION,
            settings = EngineSettings.defaults(network),
            updatedBy = null,
            reason = null,
            updatedAt = clock.instant(),
        )

    companion object {
        const val FIRST_VERSION = 1L
        private val ALL = Network.entries.flatMap { network -> Direction.entries.map { network to it } }
    }
}
