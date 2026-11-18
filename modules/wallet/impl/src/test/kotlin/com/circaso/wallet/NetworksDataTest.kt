package com.circaso.wallet

import com.circaso.infrastructure.api.Database
import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.integrations.api.CustodyAsset
import com.circaso.integrations.api.StablecoinCustody
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Network
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.WalletNetwork
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.WalletId
import com.circaso.wallet.data.repositories.DatabaseTransactor
import com.circaso.wallet.data.repositories.NetworkAccessRepositoryImpl
import com.circaso.wallet.data.repositories.NetworkSettingsRepositoryImpl
import com.circaso.wallet.data.repositories.PaymentHoldsImpl
import com.circaso.wallet.data.repositories.StoredFees
import com.circaso.wallet.data.repositories.TransferRepositoryImpl
import com.circaso.wallet.data.repositories.WalletOwnerRepositoryImpl
import com.circaso.wallet.data.repositories.WalletRepositoryImpl
import com.circaso.wallet.di.walletDatabaseSpec
import com.circaso.wallet.domain.models.AccessSource
import com.circaso.wallet.domain.models.ChainSettings
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.ChainWalletState
import com.circaso.wallet.domain.models.CircleUser
import com.circaso.wallet.domain.models.HoldReason
import com.circaso.wallet.domain.models.NetworkGrant
import com.circaso.wallet.domain.models.NetworkState
import com.circaso.wallet.domain.models.PaymentHold
import com.circaso.wallet.domain.models.Transfer
import com.circaso.wallet.domain.models.TransferOrigin
import com.circaso.wallet.domain.models.TransferState
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.domain.support.NetworkAccess
import com.circaso.wallet.domain.support.Networks
import com.circaso.wallet.domain.usecases.ListNetworks
import com.circaso.wallet.domain.usecases.UpdateNetworkSettings
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Networks on PostgreSQL (V12, decision 2026-11-18): the settings are seeded once and then the tables win; a person's
 * mainnet wallet and testnet wallet are separate rows that never see each other; spending is counted per network; and
 * the rules staff's changes to a network's settings must keep.
 */
class NetworksDataTest {
    private val database: Database = TestPostgres.database(walletDatabaseSpec)
    private val now: Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)
    private val clock = MutableClock(now)
    private val wallets = WalletRepositoryImpl(database)
    private val owners = WalletOwnerRepositoryImpl(database)
    private val transfers = TransferRepositoryImpl(database)
    private val settings = NetworkSettingsRepositoryImpl(database)
    private val grants = NetworkAccessRepositoryImpl(database)
    private val custody: StablecoinCustody =
        object : StablecoinCustody by FakeCustody() {
            override val networks: Set<Network> = Network.entries.toSet()
        }
    private val seedFees = FakeFeeConfig()
    private val networks = Networks(settings, TestSeeds, seedFees, custody, RecordingWarnings(), clock)
    private val update = UpdateNetworkSettings(settings, networks, DatabaseTransactor(database), custody, clock)
    private val staff = UUID.randomUUID()

    @BeforeTest
    fun clean() = TestPostgres.truncate("wallet")

    private suspend fun <T> on(network: Network, block: suspend () -> T): T =
        withContext(WalletNetwork(network)) {
            block()
        }

    private fun wallet(userId: UserId, chain: String) =
        ChainWallet(
            "cw-${UUID.randomUUID()}",
            userId,
            chain,
            "SCA",
            "0x${UUID.randomUUID()}",
            ChainWalletState.ACTIVE,
            now,
        )

    private fun transfer(
        sender: UserId,
        chain: String,
        usd: Long,
    ) = Transfer(
        transferId = TransferId.new(),
        senderId = sender,
        senderWalletId = "w-$chain",
        blockchain = chain,
        idempotencyKey = UUID.randomUUID(),
        recipientId = UserId.new(),
        destinationAddress = "0xabc",
        amount = Money(usd, Asset.USDC),
        state = TransferState.CONFIRMED,
        circleTxId = null,
        txHash = null,
        chatId = null,
        origin = TransferOrigin.APP,
        proposalId = null,
        failureReason = null,
        createdAt = now,
        updatedAt = now,
        usdTotalMinor = usd,
    )

    @Test
    fun `a network's settings are seeded once from the defaults, and then the tables win`() =
        runBlocking<Unit> {
            val seeded = networks.fresh()

            val testnet = seeded.settings.getValue(Network.TESTNET)
            val mainnet = seeded.settings.getValue(Network.MAINNET)
            assertEquals(NetworkState.ON, testnet.state)
            assertEquals("test-app", testnet.appId)
            assertEquals(listOf("BASE-SEPOLIA", "SOL-DEVNET", "MATIC-AMOY"), testnet.chains.map { it.blockchain })
            assertEquals(
                listOf("USDC", "ETH"),
                testnet.chains
                    .first()
                    .assets
                    .map { it.symbol },
            )
            assertEquals(false, testnet.chains.last().receiveEnabled)
            assertEquals("EOA", testnet.chains[1].accountType)
            assertEquals(NetworkState.OFF, mainnet.state)
            assertEquals("BASE", mainnet.defaultChain)
            assertEquals(FakeFeeConfig.FEE_WALLET, seeded.fees.getValue("BASE-SEPOLIA").feeAddress)
            assertNull(seeded.fees.getValue("SOL-DEVNET").feeAddress)

            // The environment changes afterwards: nothing is read from it again.
            seedFees.addresses["BASE-SEPOLIA"] = "0xother"
            TestPostgres.superuser { connection ->
                connection.createStatement().use {
                    it.execute("UPDATE wallet.network_settings SET app_id = 'from-the-admin' WHERE network = 'TESTNET'")
                    it.execute("UPDATE wallet.chain_fees SET fee_address = '0xset' WHERE blockchain = 'SOL-DEVNET'")
                }
            }
            networks.invalidate()
            val again = networks.fresh()

            assertEquals("from-the-admin", again.settings.getValue(Network.TESTNET).appId)
            assertEquals("from-the-admin", networks.current(Network.TESTNET)?.appId, "custody reads the same settings")
            val fees = StoredFees(networks, seedFees)
            assertEquals(FakeFeeConfig.FEE_WALLET, fees.feeAddress("BASE-SEPOLIA"))
            assertEquals("0xset", fees.feeAddress("SOL-DEVNET"), "an address written to the table is in force")
        }

    @Test
    fun `a person's mainnet wallet is a separate Circle user and separate wallets`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val testUser = CircleUser(amina, UUID.randomUUID(), now)
            val mainUser = CircleUser(amina, UUID.randomUUID(), now)

            assertTrue(wallets.insertCircleUser(testUser), "no network set is the testnet")
            assertTrue(on(Network.MAINNET) { wallets.insertCircleUser(mainUser) })
            assertTrue(
                !on(Network.MAINNET) { wallets.insertCircleUser(mainUser.copy(circleUserId = UUID.randomUUID())) },
            )
            wallets.insertWallet(wallet(amina, "BASE-SEPOLIA"))
            wallets.insertWallet(wallet(amina, "BASE"))
            wallets.insertWallet(wallet(amina, "SOL"))

            assertEquals(testUser.circleUserId, wallets.circleUser(amina)?.circleUserId)
            assertEquals(testUser.circleUserId, on(Network.TESTNET) { wallets.circleUser(amina) }?.circleUserId)
            assertEquals(mainUser.circleUserId, on(Network.MAINNET) { wallets.circleUser(amina) }?.circleUserId)
            assertEquals(Network.MAINNET, wallets.circleUserByCircleId(mainUser.circleUserId)?.network)
            assertEquals(listOf("BASE-SEPOLIA"), wallets.wallets(amina).map { it.blockchain })
            assertEquals(
                setOf("BASE", "SOL"),
                on(Network.MAINNET) { wallets.wallets(amina) }.map { it.blockchain }.toSet(),
            )
        }

    @Test
    fun `the app's list names the networks a person may use and where each wallet stands`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val list = ListNetworks(NetworkAccess(networks, grants, clock), wallets)
            wallets.insertCircleUser(CircleUser(amina, UUID.randomUUID(), now))
            wallets.insertWallet(wallet(amina, "BASE-SEPOLIA"))
            grants.grant(NetworkGrant(amina, Network.TESTNET, AccessSource.GRANDFATHERED, null, null, now))

            val before = (list(amina) as Resource.Success).data
            assertEquals(
                listOf(Network.TESTNET to WalletStatus.ACTIVE),
                before.networks.map {
                    it.network to
                        it.walletStatus
                },
            )
            assertEquals(Network.TESTNET, before.default)

            val opened = networks.settings(Network.MAINNET).copy(state = NetworkState.ON, appId = "main-app")
            assertTrue(update(opened, null, staff) is Resource.Success)
            on(Network.MAINNET) { wallets.insertCircleUser(CircleUser(amina, UUID.randomUUID(), now)) }

            val after = (list(amina) as Resource.Success).data
            assertEquals(
                listOf(Network.MAINNET to WalletStatus.SETTING_UP, Network.TESTNET to WalletStatus.ACTIVE),
                after.networks.map { it.network to it.walletStatus },
            )
            assertEquals(Network.MAINNET, after.default)
            val newcomer = (list(UserId.new()) as Resource.Success).data
            assertEquals(
                listOf(Network.MAINNET to WalletStatus.NONE),
                newcomer.networks.map {
                    it.network to
                        it.walletStatus
                },
            )
        }

    @Test
    fun `what was spent is counted per network, and the lock is on that network's wallet`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            wallets.insertCircleUser(CircleUser(amina, UUID.randomUUID(), now))
            on(Network.MAINNET) { wallets.insertCircleUser(CircleUser(amina, UUID.randomUUID(), now)) }
            transfers.insert(transfer(amina, "BASE-SEPOLIA", 900_000_000))
            transfers.insert(transfer(amina, "SOL-DEVNET", 50_000_000))
            transfers.insert(transfer(amina, "BASE", 20_000_000))
            val since = now.minusSeconds(3_600)

            assertEquals(950_000_000, transfers.spentSince(amina, since).sumOf { it.usdTotalMinor })
            assertEquals(
                950_000_000,
                on(Network.TESTNET) { transfers.spentSince(amina, since) }.sumOf { it.usdTotalMinor },
            )
            assertEquals(
                20_000_000,
                on(Network.MAINNET) { transfers.spentSince(amina, since) }.sumOf { it.usdTotalMinor },
            )
            on(Network.MAINNET) { DatabaseTransactor(database).run("test.lock") { transfers.lockHolder(amina) } }
            assertEquals(
                listOf("BASE"),
                on(Network.MAINNET) {
                    transfers.inState(TransferState.CONFIRMED, since, now.plusSeconds(1), 10)
                }.map { it.blockchain },
                "a job on mainnet sees mainnet's transfers only",
            )
        }

    @Test
    fun `a group has one wallet per network, and a hold pauses one network`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            val chat = ChatId.new()
            val testWallet = WalletOwner.Group(WalletId.new(), chat, amina, amina, now)
            val mainWallet = WalletOwner.Group(WalletId.new(), chat, amina, amina, now)

            assertTrue(owners.insert(testWallet))
            assertTrue(!owners.insert(testWallet.copy(walletId = WalletId.new())), "one per group on a network")
            assertTrue(on(Network.MAINNET) { owners.insert(mainWallet) })

            assertEquals(testWallet.walletId, owners.byChat(chat)?.walletId)
            assertEquals(mainWallet.walletId, on(Network.MAINNET) { owners.byChat(chat) }?.walletId)
            assertEquals(Network.MAINNET, on(Network.MAINNET) { owners.byChat(chat) }?.network)
            assertNull(
                on(Network.MAINNET) { owners.find(testWallet.walletId) },
                "the other network's wallet isn't found",
            )
            assertEquals(
                listOf(mainWallet.walletId),
                on(Network.MAINNET) { owners.bySigner(amina) }.map { it.walletId },
            )

            val holds = PaymentHoldsImpl(database, Clock.fixed(now, java.time.ZoneOffset.UTC))
            on(Network.MAINNET) { holds.place(amina, PaymentHold(now.plusSeconds(60), HoldReason.VAULT_OPENED)) }
            assertNull(holds.active(amina))
            assertEquals(HoldReason.VAULT_OPENED, on(Network.MAINNET) { holds.active(amina) }?.reason)
        }

    @Test
    fun `access is granted once and revoked without touching the wallet`() =
        runBlocking<Unit> {
            val amina = UserId.new()
            wallets.insertCircleUser(CircleUser(amina, UUID.randomUUID(), now))
            val grant = NetworkGrant(amina, Network.TESTNET, AccessSource.ADMIN, staff, "asked for it", now)

            assertTrue(grants.grant(grant))
            assertTrue(!grants.grant(grant.copy(source = AccessSource.AUTO)), "a second grant changes nothing")
            assertEquals(listOf(grant), grants.grants(amina))
            assertTrue(grants.revoke(amina, Network.TESTNET))
            assertTrue(!grants.revoke(amina, Network.TESTNET))

            assertEquals(emptyList(), grants.grants(amina))
            assertTrue(wallets.circleUser(amina) != null, "revoking never deletes the wallet")
        }

    @Test
    fun `staff may change a network's settings within the rules`() =
        runBlocking<Unit> {
            val current = networks.settings(Network.MAINNET)

            fun code(result: Resource<*>) = (result as Resource.Error).error.code
            val usdc = current.chains.first().assets
            val arb = ChainSettings("ARB", enabled = true, receiveEnabled = true, accountType = "SCA", assets = usdc)

            assertEquals(
                "wallet.network_settings_invalid",
                code(update(current.copy(state = NetworkState.ON), null, staff)),
                "no app id",
            )
            val open = current.copy(state = NetworkState.PREVIEW, appId = " main-app ", chains = current.chains + arb)
            val saved = (update(open, current.version, staff) as Resource.Success).data.second
            assertEquals(current.version + 1, saved.version)
            assertEquals("main-app", saved.appId)
            assertEquals(saved, networks.settings(Network.MAINNET))
            assertEquals(NetworkState.PREVIEW, networks.state(Network.MAINNET))
            assertEquals(
                listOf("BASE", "SOL", "ARB"),
                networks
                    .current(Network.MAINNET)
                    ?.supported
                    ?.chains
                    ?.map { it.blockchain },
            )

            assertEquals(
                "wallet.network_settings_changed",
                code(update(saved, current.version, staff)),
                "a stale version",
            )
            val testnetChain = arb.copy(blockchain = "ARB-SEPOLIA")
            assertEquals(
                "wallet.network_settings_invalid",
                code(update(saved.copy(chains = saved.chains + testnetChain), null, staff)),
            )
            assertEquals(
                "wallet.network_settings_invalid",
                code(update(saved.copy(defaultChain = "MATIC"), null, staff)),
            )
            val retiredDefault =
                saved.chains.map {
                    if (it.blockchain ==
                        "BASE"
                    ) {
                        it.copy(receiveEnabled = false)
                    } else {
                        it
                    }
                }
            assertEquals(
                "wallet.network_settings_invalid",
                code(update(saved.copy(chains = retiredDefault), null, staff)),
            )
            val solAsSca = saved.chains.map { if (it.blockchain == "SOL") it.copy(accountType = "SCA") else it }
            assertEquals("wallet.network_settings_invalid", code(update(saved.copy(chains = solAsSca), null, staff)))
            val noAssets =
                saved.chains.map {
                    if (it.blockchain ==
                        "ARB"
                    ) {
                        it.copy(assets = emptyList<CustodyAsset>())
                    } else {
                        it
                    }
                }
            assertEquals("wallet.network_settings_invalid", code(update(saved.copy(chains = noAssets), null, staff)))
        }

    @Test
    fun `a chain with wallets on it can be retired but not removed or disabled`() =
        runBlocking<Unit> {
            val current = networks.settings(Network.MAINNET).copy(appId = "main-app")
            wallets.insertWallet(wallet(UserId.new(), "SOL"))

            fun code(result: Resource<*>) = (result as Resource.Error).error.code

            val removed = current.copy(chains = current.chains.filter { it.blockchain != "SOL" })
            val disabled =
                current.copy(
                    chains =
                        current.chains.map {
                            if (it.blockchain ==
                                "SOL"
                            ) {
                                it.copy(enabled = false)
                            } else {
                                it
                            }
                        },
                )
            val retired =
                current.copy(
                    chains =
                        current.chains.map {
                            if (it.blockchain ==
                                "SOL"
                            ) {
                                it.copy(receiveEnabled = false)
                            } else {
                                it
                            }
                        },
                )

            assertEquals("wallet.network_settings_invalid", code(update(removed, null, staff)))
            assertEquals("wallet.network_settings_invalid", code(update(disabled, null, staff)))
            assertTrue(update(retired, null, staff) is Resource.Success)
            networks.fresh()
            assertEquals(
                false,
                networks
                    .current(Network.MAINNET)
                    ?.supported
                    ?.find("SOL")
                    ?.receiveEnabled,
            )
            assertEquals(
                current.chains.map { it.blockchain },
                networks.settings(Network.MAINNET).chains.map { it.blockchain },
            )
        }
}
