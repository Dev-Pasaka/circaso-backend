package com.circaso.integrations.impl

import com.circaso.infrastructure.api.Config
import com.circaso.infrastructure.api.Secrets
import com.circaso.integrations.api.DEV_TREASURY
import com.circaso.integrations.api.TreasuryCustody
import com.circaso.integrations.impl.circle.CircleAccounts
import com.circaso.integrations.impl.circle.CircleTreasury
import com.circaso.integrations.impl.circle.EntitySecret
import com.circaso.integrations.impl.circle.TreasuryAccount
import com.circaso.integrations.impl.dev.FakeTreasury
import com.circaso.integrations.impl.dev.LogTreasury
import com.circaso.shared.Network
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module
import org.slf4j.LoggerFactory

/**
 * Circaso's own wallets at Circle (the fee wallets, decision 2026-11-18), for the **ops process only**: `OpsMain`
 * loads this module, the app's `serve` never does, so the app's processes neither read nor need the entity secrets and
 * start exactly as they did without them.
 *
 * The secrets: `CIRCLE_ENTITY_SECRET` for the testnet account and `CIRCLE_MAINNET_ENTITY_SECRET` for the mainnet one
 * (64 hex characters each). Either may be absent: the ops server starts all the same, reads what the API key alone can
 * read (wallets, balances), and can't create fee wallets or withdraw on that network. One that isn't 64 hex characters
 * is treated as absent and said so in the log, by name, never by value.
 *
 * Bound only when custody is Circle (`integrations.custody.provider = circle`); with `log` or `fake` there is no
 * treasury and the fee wallets report `NOT_CREATED`.
 *
 * With `log` or `fake` a stand-in is bound under the name [DEV_TREASURY] instead (decision 2026-11-22), for the ramp
 * alone: its release must run end to end locally with no money anywhere. It is named, so a plain
 * `get<TreasuryCustody>()` still finds nothing and the fee wallets behave exactly as before.
 */
fun treasuryModule(config: Config, secrets: Secrets): Module =
    module {
        val provider = config.section("integrations").string("custody.provider", "log")
        if (provider == "fake") single<TreasuryCustody>(named(DEV_TREASURY)) { get<FakeTreasury>() }
        if (provider == "log") {
            single<TreasuryCustody>(named(DEV_TREASURY)) { LogTreasury(usdcContracts(get())) }
        }
        if (provider == "circle") {
            single<TreasuryCustody> {
                val accounts = get<CircleAccounts>().accounts
                CircleTreasury(
                    accounts.mapValues { (network, account) ->
                        TreasuryAccount(account.client, entitySecret(secrets, network))
                    },
                )
            }
        }
    }

internal fun entitySecretName(network: Network): String =
    if (network == Network.TESTNET) "circle.entity-secret" else "circle.mainnet-entity-secret"

internal fun entitySecret(secrets: Secrets, network: Network): EntitySecret? {
    val name = entitySecretName(network)
    val raw = secrets.get(name)?.takeIf { it.isNotBlank() } ?: return null
    return EntitySecret.parse(raw) ?: run {
        LoggerFactory
            .getLogger("com.circaso.integrations.impl.TreasuryModule")
            .error(
                "secret '{}' isn't 64 hex characters; fee wallets on {} can't be created or withdrawn from",
                name,
                network,
            )
        null
    }
}
