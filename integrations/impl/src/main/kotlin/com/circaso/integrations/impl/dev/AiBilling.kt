package com.circaso.integrations.impl.dev

import com.circaso.integrations.api.AiBill
import com.circaso.integrations.api.AiBilling
import com.circaso.integrations.api.IntegrationError
import com.circaso.shared.Resource
import java.time.LocalDate

/** No admin key: the provider's billing is never asked, and the cost report says how to turn it on. */
object NoAiBilling : AiBilling {
    override val isEnabled: Boolean = false

    override suspend fun billed(from: LocalDate, until: LocalDate): Resource<AiBill> =
        Resource.error(IntegrationError.Unavailable("AI billing", isTransient = false))
}
