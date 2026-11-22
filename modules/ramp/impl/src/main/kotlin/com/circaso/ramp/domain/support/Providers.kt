package com.circaso.ramp.domain.support

import com.circaso.integrations.api.CollectionProviders

/**
 * The collections providers the ramp may use (decision 2026-11-22): whichever integrations offers on this server, the
 * one in force being a setting staff switch in the admin per network, never a start-up line.
 */
object Providers {
    /** What a network's first row names: `log` where this server offers it (a developer's machine), else PayHero. */
    fun seed(offered: CollectionProviders): String =
        if (CollectionProviders.LOG in offered.names) CollectionProviders.LOG else CollectionProviders.PAYHERO
}
