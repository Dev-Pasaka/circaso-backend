package com.circaso.ramp.domain.support

import com.circaso.ramp.domain.models.PricingSettings
import com.circaso.ramp.domain.ports.RampAlerts
import com.circaso.ramp.domain.repositories.PricingSettingsRepository
import com.circaso.shared.Network
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * Each network's pricing settings in force (decision 2026-11-24), read the way the ramp's own settings are
 * ([SettingsBook]): from `ramp.pricing_settings` at most once every [maxAge] per process, so a rate staff set applies
 * everywhere within seconds with no restart; a network with no row yet is seeded once from the defaults (spreads of
 * 0: the reference rate on both sides); when the table can't be read, what was read last stays in force.
 */
class PricingBook(
    private val repository: PricingSettingsRepository,
    private val alerts: RampAlerts,
    private val clock: Clock,
    private val maxAge: Duration = SettingsBook.MAX_AGE,
) {
    private class Loaded(val rows: Map<Network, PricingSettings>, val at: Instant)

    @Volatile
    private var loaded: Loaded? = null

    /** [network]'s pricing settings now (at most [maxAge] old). */
    suspend fun of(network: Network): PricingSettings =
        fresh()[network] ?: PricingSettings.defaults(network, clock.instant()).copy(version = 0)

    /** Makes the next read go to the table (after a change made in this process). */
    fun invalidate() {
        loaded = loaded?.let { Loaded(it.rows, Instant.EPOCH) }
    }

    private suspend fun fresh(): Map<Network, PricingSettings> {
        val current = loaded
        if (current != null && clock.instant().isBefore(current.at.plus(maxAge))) return current.rows
        return try {
            read().also { loaded = Loaded(it, clock.instant()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A database hiccup must not change a rate: the last read stays in force.
            alerts.stuck("pricing settings", e::class.simpleName.orEmpty())
            current?.rows ?: emptyMap()
        }
    }

    private suspend fun read(): Map<Network, PricingSettings> {
        val stored = repository.load()
        val missing = Network.entries.filter { it !in stored }
        if (missing.isEmpty()) return stored
        missing.forEach { repository.seed(PricingSettings.defaults(it, clock.instant())) }
        return repository.load()
    }
}
