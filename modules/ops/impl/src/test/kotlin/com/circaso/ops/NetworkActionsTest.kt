package com.circaso.ops

import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.Permission
import com.circaso.ops.domain.usecases.GetDashboard
import com.circaso.ops.domain.usecases.GetUserAccount
import com.circaso.ops.domain.usecases.GetUserWallets
import com.circaso.ops.domain.usecases.SetUserNetworkAccess
import com.circaso.ops.domain.usecases.UpdateWalletNetwork
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.StaffRole
import com.circaso.shared.UserId
import com.circaso.shared.WalletNetwork
import com.circaso.shared.getOrNull
import com.circaso.wallet.api.OpsNetworkAsset
import com.circaso.wallet.api.OpsNetworkChain
import com.circaso.wallet.api.OpsNetworkSettings
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Staff and the wallet networks (decision 2026-11-18): a person's access, the settings, and the `?network=` filter. */
class NetworkActionsTest {
    private val harness = OpsHarness()
    private val koin = harness.koin
    private val admin = Actor(UUID.randomUUID(), "admin@circaso.com", StaffRole.ADMIN, "req-1")

    @Test
    fun `only admins grant network access or change the settings, and finance may see the fee wallets`() {
        assertEquals(setOf(StaffRole.ADMIN), Permission.USERS_NETWORK.roles)
        assertEquals(setOf(StaffRole.ADMIN), Permission.WALLET_SETTINGS.roles)
        assertEquals(setOf(StaffRole.ADMIN, StaffRole.FINANCE), Permission.FEES_WALLET_READ.roles)
        assertEquals(setOf(StaffRole.ADMIN), Permission.FEES_WITHDRAW.roles)
        assertEquals(
            listOf("users.network", "wallet.settings", "fees.wallet.read", "fees.withdraw"),
            listOf(
                Permission.USERS_NETWORK,
                Permission.WALLET_SETTINGS,
                Permission.FEES_WALLET_READ,
                Permission.FEES_WITHDRAW,
            ).map { it.wire },
        )
    }

    @Test
    fun `a grant needs a reason, is audited, and shows on the person, and a revoke takes it away again`() =
        runTest {
            val user = harness.accounts.add()
            val set = koin.get<SetUserNetworkAccess>()

            val refused = set(admin, user, Network.TESTNET, true, " ")
            val granted = set(admin, user, Network.TESTNET, true, "Tester for the Nairobi pilot").getOrNull()
            val again = set(admin, user, Network.TESTNET, true, "Tester for the Nairobi pilot").getOrNull()

            assertEquals(Resource.error(OpsError.ReasonRequired), refused)
            assertEquals(listOf(Network.TESTNET), granted?.networkAccess?.map { it.network })
            assertEquals("ADMIN", granted?.networkAccess?.single()?.source)
            assertEquals(admin.staffId, granted?.networkAccess?.single()?.grantedBy)
            assertEquals(granted?.networkAccess, again?.networkAccess)
            assertEquals(granted?.networkAccess, koin.get<GetUserAccount>()(user).getOrNull()?.networkAccess)

            val revoked = set(admin, user, Network.TESTNET, false, "Pilot over").getOrNull()
            assertEquals(emptyList(), revoked?.networkAccess)

            val lines = harness.audit.entries.filter { it.action == "user.network_access" }
            assertEquals(3, lines.size, "a refused reason leaves no line")
            assertTrue(lines.all { it.targetId == user.toString() && it.targetType == "user" })
            assertEquals(
                listOf("grant TESTNET", "grant TESTNET (no change)", "revoke TESTNET"),
                lines.map { it.detail },
            )
        }

    @Test
    fun `access for someone who isn't there is refused before anything is written`() =
        runTest {
            val result = koin.get<SetUserNetworkAccess>()(admin, UserId.new(), Network.MAINNET, true, "Staff")

            assertEquals(Resource.error(OpsError.UserNotFound), result)
            assertTrue(harness.audit.entries.isEmpty())
        }

    @Test
    fun `a settings change needs a reason and is audited with what changed`() =
        runTest {
            val update = koin.get<UpdateWalletNetwork>()
            val usdc = OpsNetworkAsset("USDC", "0x833589fCD6eDb6E08f4c7C32D4f71b54bdA02913", 6, usd = true)
            val settings =
                OpsNetworkSettings(
                    network = Network.MAINNET,
                    state = "PREVIEW",
                    appId = "main-app",
                    defaultChain = "BASE",
                    chains = listOf(OpsNetworkChain("BASE", true, true, "SCA", listOf(usdc))),
                    version = 1,
                )

            assertEquals(Resource.error(OpsError.ReasonRequired), update(admin, settings, ""))
            val changed = update(admin, settings, "Staff smoke test before launch")

            assertIs<Resource.Success<*>>(changed)
            assertEquals(listOf(settings), harness.networks.saved)
            val line = harness.audit.entries.single { it.action == "wallet_network.update" }
            assertEquals("MAINNET", line.targetId)
            assertEquals("wallet_network", line.targetType)
            assertEquals("Staff smoke test before launch", line.reason)
            assertEquals("v1→v2; state OFF→PREVIEW; app id changed; added BASE", line.detail)
        }

    @Test
    fun `the wallet's refusal of a settings change is audited as failed with its code`() =
        runTest {
            harness.networks.refusal = OpsError.ReasonRequired
            val settings =
                harness.networks.current
                    .first()
                    .copy(state = "ON")

            val result = koin.get<UpdateWalletNetwork>()(admin, settings, "Launch")

            assertIs<Resource.Error>(result)
            assertEquals(
                "ops.reason_required",
                harness.audit.entries
                    .single()
                    .errorCode,
            )
        }

    @Test
    fun `money reads ask the wallet for the network staff named, or for both when none is`() =
        runTest {
            val user = harness.accounts.add()

            koin.get<GetUserWallets>()(user)
            withContext(WalletNetwork(Network.MAINNET)) {
                koin.get<GetUserWallets>()(user)
                koin.get<GetDashboard>()(StaffRole.FINANCE, 7)
            }

            assertEquals(listOf(null, Network.MAINNET, Network.MAINNET), harness.wallets.networksAsked.toList())
        }
}
