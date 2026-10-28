package com.circaso.wallet

import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.AddChainCommand
import com.circaso.wallet.domain.models.CreateWalletCommand
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.WalletOutbox
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.WalletSync
import com.circaso.wallet.domain.usecases.AddChain
import com.circaso.wallet.domain.usecases.CheckFundsBeforeDeletion
import com.circaso.wallet.domain.usecases.CreateWallet
import com.circaso.wallet.domain.usecases.GetWallet
import com.circaso.wallet.domain.usecases.HasWallet
import com.circaso.wallet.domain.usecases.ListChains
import com.circaso.wallet.domain.usecases.OpenSession
import java.time.Clock
import java.util.UUID

/**
 * The wallet's use cases over in-memory fakes of their ports, or over the real repository and transactor when a data
 * test passes them in.
 */
class Harness(
    val wallets: WalletRepository = MemoryWalletRepository(),
    val transactor: Transactor = DirectTransactor,
    val outbox: WalletOutbox = RecordingOutbox(),
) {
    val custody = FakeCustody()
    val rates = FakeRates()
    val bus = MemoryBus()
    val cache = MemoryBalanceCache()
    val adding = MemoryAddingChains()
    val clock: Clock = Clock.systemUTC()

    val sync = WalletSync(wallets, transactor, outbox, bus, custody, adding, clock)
    private val holdings = HoldingsReader(custody, cache)

    val getWallet = GetWallet(wallets, sync, adding, holdings, rates)
    val createWallet = CreateWallet(wallets, transactor, custody, sync, clock)
    val openSession = OpenSession(wallets, custody)
    val listChains = ListChains(wallets, sync, adding, custody)
    val addChain = AddChain(wallets, sync, adding, custody)
    val checkFunds = CheckFundsBeforeDeletion(wallets, sync, holdings)
    val hasWallet = HasWallet(wallets)

    suspend fun wallet(userId: UserId, currency: String? = null) = getWallet(WalletQuery(userId, currency))

    suspend fun create(userId: UserId, key: UUID = UUID.randomUUID()) = createWallet(CreateWalletCommand(userId, key))

    suspend fun add(
        userId: UserId,
        blockchain: String,
        key: UUID = UUID.randomUUID(),
    ) = addChain(AddChainCommand(userId, blockchain, key))

    /** The Circle user id [userId]'s wallet was created under. */
    suspend fun circleUserOf(userId: UserId): String = wallets.circleUser(userId)!!.circleUserId.toString()

    /** A wallet created and signed: `ACTIVE` on the default chain, recorded by a W1 read. */
    suspend fun activeWallet(userId: UserId): String {
        create(userId).data()
        val circleUserId = circleUserOf(userId)
        custody.sign(circleUserId)
        wallet(userId).data()
        return circleUserId
    }
}

fun <T> Resource<T>.data(): T = (this as Resource.Success).data

fun <T> Resource<T>.code(): String = (this as Resource.Error).error.code
