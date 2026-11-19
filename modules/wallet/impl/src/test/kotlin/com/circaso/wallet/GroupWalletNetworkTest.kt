package com.circaso.wallet

import com.circaso.integrations.api.StablecoinCustody
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Network
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.AccessSource
import com.circaso.wallet.domain.models.CreateGroupWalletCommand
import com.circaso.wallet.domain.models.NetworkGrant
import com.circaso.wallet.domain.models.NetworkState
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.domain.support.NetworkAccess
import com.circaso.wallet.domain.support.Networks
import kotlinx.coroutines.runBlocking
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A new group has one wallet, on mainnet (Pasaka, 2026-11-19): W14 on the testnet (the default network of these
 * tests) is refused for an owner who may use mainnet, unless the group's testnet wallet was already started.
 */
class GroupWalletNetworkTest {
    private val h = Harness()
    private val settings = MemoryNetworkSettings()
    private val grants = MemoryNetworkAccess()
    private val custody: StablecoinCustody =
        object : StablecoinCustody by FakeCustody() {
            override val networks: Set<Network> get() = Network.entries.toSet()
        }
    private val networks = Networks(settings, TestSeeds, FakeFeeConfig(), custody, RecordingWarnings(), h.clock)
    private val access = NetworkAccess(networks, grants, h.clock)
    private val create = h.createGroupWalletWith(access)
    private val amina = UserId.new() // owns the groups; had a testnet wallet before networks
    private val otieno = UserId.new()
    private val wanjiru = UserId.new()
    private val chat: ChatId = h.chats.group(listOf(amina, otieno, wanjiru))

    init {
        grants.rows += NetworkGrant(amina, Network.TESTNET, AccessSource.GRANDFATHERED, null, null, Instant.EPOCH)
    }

    private fun mainnet(state: NetworkState) =
        runBlocking {
            networks.fresh()
            settings.state(Network.MAINNET, state)
            networks.invalidate()
        }

    private suspend fun setUp(chatId: ChatId = chat) =
        create(CreateGroupWalletCommand(amina, chatId, UUID.randomUUID()))

    @Test
    fun `before mainnet opens a group's wallet is still made on the testnet`() =
        runBlocking<Unit> {
            assertEquals(WalletStatus.SETTING_UP, setUp().data().setup.status)
        }

    @Test
    fun `once its owner may use mainnet a new group gets no testnet wallet`() =
        runBlocking<Unit> {
            mainnet(NetworkState.ON)
            assertEquals("wallet.group_mainnet_only", setUp().code())
        }

    @Test
    fun `in preview only an owner let into mainnet is held to it`() =
        runBlocking<Unit> {
            mainnet(NetworkState.PREVIEW)
            assertEquals(WalletStatus.SETTING_UP, setUp().data().setup.status, "not in the preview: testnet as before")
            val second = h.chats.group(listOf(amina, otieno, wanjiru))
            grants.rows += NetworkGrant(amina, Network.MAINNET, AccessSource.ADMIN, null, null, Instant.EPOCH)
            assertEquals("wallet.group_mainnet_only", setUp(second).code())
        }

    @Test
    fun `a testnet set-up already started can still be finished after mainnet opens`() =
        runBlocking<Unit> {
            setUp().data()
            mainnet(NetworkState.ON)
            assertEquals(WalletStatus.SETTING_UP, setUp().data().setup.status)
        }
}
