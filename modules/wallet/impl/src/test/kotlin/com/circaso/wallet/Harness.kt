package com.circaso.wallet

import com.circaso.integrations.api.CustodyDirection
import com.circaso.integrations.api.CustodyTransaction
import com.circaso.integrations.api.CustodyTransactionState
import com.circaso.integrations.api.SupportedChains
import com.circaso.messaging.api.ChatId
import com.circaso.shared.Asset
import com.circaso.shared.Money
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.wallet.domain.models.AddChainCommand
import com.circaso.wallet.domain.models.ChainWallet
import com.circaso.wallet.domain.models.CreateWalletCommand
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.TransferLimits
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.repositories.WalletOutbox
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.IncomingUpdates
import com.circaso.wallet.domain.support.OrderedIds
import com.circaso.wallet.domain.support.OutgoingUpdates
import com.circaso.wallet.domain.support.Payees
import com.circaso.wallet.domain.support.SenderRules
import com.circaso.wallet.domain.support.TransferEvents
import com.circaso.wallet.domain.support.WalletSync
import com.circaso.wallet.domain.usecases.AddChain
import com.circaso.wallet.domain.usecases.ApplyCustodyUpdate
import com.circaso.wallet.domain.usecases.CheckFundsBeforeDeletion
import com.circaso.wallet.domain.usecases.CheckRecipient
import com.circaso.wallet.domain.usecases.CreateWallet
import com.circaso.wallet.domain.usecases.GetTransfer
import com.circaso.wallet.domain.usecases.GetWallet
import com.circaso.wallet.domain.usecases.HasWallet
import com.circaso.wallet.domain.usecases.ListChains
import com.circaso.wallet.domain.usecases.LookupTransfer
import com.circaso.wallet.domain.usecases.OpenSession
import com.circaso.wallet.domain.usecases.PrepareTransfer
import com.circaso.wallet.domain.usecases.ReplayFrames
import com.circaso.wallet.domain.usecases.RequestSetup
import com.circaso.wallet.domain.usecases.ValidateAddress
import com.circaso.wallet.presentation.services.WalletDirectoryService
import java.math.BigInteger
import java.util.UUID

/** 500 USDC a payment, 1 000 a day, in minor units (the defaults of the settings). */
val TEST_LIMITS = TransferLimits(perPaymentMinor = 500_000_000, dailyMinor = 1_000_000_000)

/**
 * The wallet's use cases over in-memory fakes of their ports, or over the real repositories and transactor when a
 * data test passes them in.
 */
class Harness(
    val wallets: WalletRepository = MemoryWalletRepository(),
    val transactor: Transactor = DirectTransactor,
    val outbox: WalletOutbox = RecordingOutbox(),
    val transfers: TransferRepository = MemoryTransferRepository(),
    val transactions: TransactionRepository = MemoryTransactionRepository(),
    chains: SupportedChains = TEST_CHAINS,
) {
    val custody = FakeCustody(chains)
    val rates = FakeRates()
    val bus = MemoryBus()
    val cache = MemoryBalanceCache()
    val adding = MemoryAddingChains()
    val clock = MutableClock()
    val frames = RecordingFrames()
    val spend = MemoryDailySpend()
    val reminders = MemoryReminders()
    val holds = MemoryHolds()
    val accounts = FakeAccounts()
    val reach = FakeReach()
    val chats = FakeChats()
    val profiles = FakeProfiles()
    val ids = OrderedIds(clock)

    val sync = WalletSync(wallets, transactor, outbox, bus, custody, adding, frames, clock)
    val warnings = RecordingWarnings()
    private val holdings = HoldingsReader(custody, cache, warnings)
    private val payees = Payees(wallets, reach, chats, custody)
    private val events = TransferEvents(bus)
    private val rules = SenderRules(wallets, accounts, holds, holdings, spend, custody, TEST_LIMITS, clock)

    val getWallet = GetWallet(wallets, sync, adding, holdings, rates, custody)
    val createWallet = CreateWallet(wallets, transactor, custody, sync, clock)
    val openSession = OpenSession(wallets, custody)
    val listChains = ListChains(wallets, sync, adding, custody)
    val addChain = AddChain(wallets, sync, adding, custody, clock)
    val checkFunds = CheckFundsBeforeDeletion(wallets, sync, holdings)
    val hasWallet = HasWallet(wallets)
    val prepareTransfer =
        PrepareTransfer(
            transfers,
            transactions,
            wallets,
            transactor,
            rules,
            payees,
            custody,
            outbox,
            bus,
            events,
            ids,
            clock,
        )
    val getTransfer = GetTransfer(transfers)
    val checkRecipient = CheckRecipient(payees, custody)
    val requestSetup = RequestSetup(payees, reminders, profiles, frames)
    val validateAddress = ValidateAddress(custody)
    val applyUpdate =
        ApplyCustodyUpdate(
            wallets,
            sync,
            OutgoingUpdates(transfers, transactions, transactor, outbox, bus, events, frames, rules, warnings, ids),
            IncomingUpdates(wallets, transactions, transactor, frames, profiles, ids),
            cache,
            warnings,
            clock,
        )

    val directory =
        WalletDirectoryService(
            hasWallet,
            checkFunds,
            applyUpdate,
            ReplayFrames(NoFramesKept),
            LookupTransfer(transfers),
        )

    suspend fun wallet(userId: UserId, currency: String? = null) = getWallet(WalletQuery(userId, currency))

    suspend fun create(userId: UserId, key: UUID = UUID.randomUUID()) = createWallet(CreateWalletCommand(userId, key))

    suspend fun add(userId: UserId, blockchain: String) = addChain(AddChainCommand(userId, blockchain))

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

    /** [userId]'s recorded wallet on [blockchain], adding the chain first (signed at once) when it isn't there. */
    suspend fun walletOn(userId: UserId, blockchain: String = TEST_CHAINS.default): ChainWallet {
        wallets.wallets(userId).firstOrNull { it.blockchain == blockchain }?.let { return it }
        if (wallets.circleUser(userId) == null) activeWallet(userId)
        wallets.wallets(userId).firstOrNull { it.blockchain == blockchain }?.let { return it }
        custody.sign(circleUserOf(userId), blockchain)
        sync.sync(wallets.circleUser(userId)!!).data()
        return wallets.wallets(userId).single { it.blockchain == blockchain }
    }

    /** What Circle holds of USDC in [userId]'s wallet on [blockchain]. */
    suspend fun fund(
        userId: UserId,
        usdcMinor: Long,
        blockchain: String = TEST_CHAINS.default,
    ) {
        val wallet = walletOn(userId, blockchain)
        custody.held[wallet.circleWalletId] = mapOf("USDC" to BigInteger.valueOf(usdcMinor))
    }

    suspend fun pay(
        sender: UserId,
        payee: Payee,
        usdcMinor: Long,
        blockchain: String = TEST_CHAINS.default,
        key: UUID = UUID.randomUUID(),
        chatId: ChatId? = null,
        proposalId: UUID? = null,
    ) = prepareTransfer(
        TransferCommand(sender, blockchain, payee, Money(usdcMinor, Asset.USDC), chatId, proposalId, key),
    )

    /** Circle's update about a transaction of [wallet], in our words, as the webhook hands it over. */
    fun update(
        wallet: ChainWallet,
        circleUserId: String,
        direction: CustodyDirection,
        state: CustodyTransactionState,
        usdcMinor: Long,
        refId: String = "",
        circleTxId: String = "tx-${UUID.randomUUID()}",
        source: String = "0x000000000000000000000000000000000000dEaD",
        destination: String = wallet.address,
    ) = CustodyTransaction(
        circleTxId = circleTxId,
        circleUserId = circleUserId,
        walletId = wallet.circleWalletId,
        blockchain = wallet.blockchain,
        direction = direction,
        state = state,
        amount = Money(usdcMinor, Asset.USDC),
        sourceAddress = source,
        destinationAddress = destination,
        refId = refId,
        txHash = if (state == CustodyTransactionState.CONFIRMED) "0xhash" else "",
        failureReason = if (state == CustodyTransactionState.FAILED) "INSUFFICIENT_TOKEN" else "",
        updatedAt = clock.instant(),
    )
}

fun <T> Resource<T>.data(): T = (this as Resource.Success).data

fun <T> Resource<T>.code(): String = (this as Resource.Error).error.code
