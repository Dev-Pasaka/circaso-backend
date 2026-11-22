package com.circaso.ops.domain.support

import com.circaso.integrations.api.EmailTemplate
import com.circaso.integrations.api.EmailTemplateSeeds
import com.circaso.ops.domain.models.EmailTemplateId
import com.circaso.ops.domain.ports.EmailTemplateWarnings
import com.circaso.ops.domain.repositories.EmailTemplateRepository
import java.time.Clock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.cancellation.CancellationException

/**
 * The email template ids in force (decision 2026-11-22: the environment holds keys and secrets only; a template's id
 * at the provider is a row staff set in the admin). Read from `ops.email_templates` at most once every [maxAge] per
 * process, so the app and the ops servers agree within seconds of a change. When the table can't be read, what was
 * read last stays in force.
 *
 * **Import once.** The first time a template has no row, the old `INTEGRATIONS_EMAIL_TEMPLATES_<NAME>` variable is
 * copied in if it is still set ([seeds]); from then on the row wins and the variable is never looked at for that
 * template again, so a server that had its ids in the environment keeps sending with no manual step.
 */
class EmailTemplateBook(
    private val repository: EmailTemplateRepository,
    private val seeds: EmailTemplateSeeds,
    private val warnings: EmailTemplateWarnings,
    private val clock: Clock,
    private val maxAge: Duration = MAX_AGE,
) {
    private class Loaded(val ids: Map<EmailTemplate, EmailTemplateId>, val at: Instant)

    @Volatile
    private var loaded: Loaded? = null

    /** The id [template] is sent with now (at most [maxAge] old); null when none is set. */
    suspend fun idOf(template: EmailTemplate): String? = fresh()[template]?.providerTemplateId

    /** Every id as the table has it now, read past the cache: what the admin lists and changes. */
    suspend fun current(): Map<EmailTemplate, EmailTemplateId> = read().also { loaded = Loaded(it, clock.instant()) }

    /** Makes the next [idOf] read the table (after a change made in this process). */
    fun invalidate() {
        loaded = loaded?.let { Loaded(it.ids, Instant.EPOCH) }
    }

    private suspend fun fresh(): Map<EmailTemplate, EmailTemplateId> {
        val kept = loaded
        if (kept != null && clock.instant().isBefore(kept.at.plus(maxAge))) return kept.ids
        return try {
            current()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            warnings.unread(e::class.simpleName.orEmpty())
            kept?.ids ?: emptyMap()
        }
    }

    private suspend fun read(): Map<EmailTemplate, EmailTemplateId> {
        val stored = repository.load()
        val seeded =
            EmailTemplate.entries
                .filter { it !in stored }
                .mapNotNull { template ->
                    seeds
                        .seed(template)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { template to it }
                }
        if (seeded.isEmpty()) return stored
        // Another process may be importing the same seed: the insert that loses changes nothing, and the row is read back.
        seeded.forEach { (template, id) -> repository.insert(template, id, by = null, at = clock.instant()) }
        return repository.load()
    }

    companion object {
        val MAX_AGE: Duration = Duration.ofSeconds(10)
    }
}
