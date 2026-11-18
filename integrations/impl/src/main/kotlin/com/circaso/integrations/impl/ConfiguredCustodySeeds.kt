package com.circaso.integrations.impl

import com.circaso.infrastructure.api.Config
import com.circaso.integrations.api.CustodySeeds
import com.circaso.integrations.api.CustodySetup
import com.circaso.shared.Network
import java.util.concurrent.ConcurrentHashMap

/** [CustodySeeds] from the `integrations.custody` settings ([CustodyChainsConfig.seed]), read once per network. */
internal class ConfiguredCustodySeeds(private val custody: Config) : CustodySeeds {
    private val seeds = ConcurrentHashMap<Network, CustodySetup>()

    override fun seed(network: Network): CustodySetup =
        seeds.computeIfAbsent(network) { CustodyChainsConfig.seed(custody, it) }
}
