package com.circaso.ramp.domain.support

import com.circaso.integrations.api.CollectionProviders
import com.circaso.ramp.domain.models.RampSettings
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.repositories.SettingsRepository
import com.circaso.shared.Network
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * The ramp's settings in force (decision 2026-11-18: the environment holds keys and secrets only; everything else is
 * a row staff edit). Read from `ramp.settings` at most once every [maxAge] per process, so the app and the ops server
 * agree within seconds of a change; a network with no row yet is seeded once from the built-in defaults, and after
 * that the database wins. When the table can't be read, what was read last stays in force.
 */
class SettingsBook(
    private val repository: SettingsRepository,
    private val alerts: RampAlerts,
    private val providers: CollectionProviders,
    private val clock: Clock,
    private val maxAge: Duration = MAX_AGE,
) {
    private class Loaded(val settings: Map<Network, RampSettings>, val at: Instant)

    @Volatile
    private var loaded: Loaded? = null

    /** [network]'s settings now (at most [maxAge] old). */
    suspend fun of(network: Network): RampSettings = fresh()[network] ?: defaults(network)

    /** Makes the next read go to the table (after a change made in this process). */
    fun invalidate() {
        loaded = loaded?.let { Loaded(it.settings, Instant.EPOCH) }
    }

    private suspend fun fresh(): Map<Network, RampSettings> {
        val current = loaded
        if (current != null && clock.instant().isBefore(current.at.plus(maxAge))) return current.settings
        return try {
            read().also { loaded = Loaded(it, clock.instant()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A database hiccup must not open or close the ramp, or change a fee: the last read stays in force. With
            // nothing read yet, the built-in defaults say the ramp is off.
            alerts.stuck("settings", e::class.simpleName.orEmpty())
            current?.settings ?: emptyMap()
        }
    }

    private suspend fun read(): Map<Network, RampSettings> {
        val stored = repository.load()
        val missing = Network.entries.filter { it !in stored }
        if (missing.isEmpty()) return stored
        missing.forEach { repository.seed(defaults(it)) }
        return repository.load()
    }

    /**
     * What a network starts as. The prompts are PayHero's, except on a developer's machine, where integrations also
     * offers `log` (every prompt reported paid) and the seed takes it so an order completes end to end.
     */
    private fun defaults(network: Network): RampSettings =
        RampSettings.defaults(network, clock.instant(), Providers.seed(providers))

    companion object {
        val MAX_AGE: Duration = Duration.ofSeconds(10)
    }
}
