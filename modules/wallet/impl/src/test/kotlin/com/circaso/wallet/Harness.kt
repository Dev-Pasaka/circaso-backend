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
import com.circaso.wallet.domain.models.JobSettings
import com.circaso.wallet.domain.models.Payee
import com.circaso.wallet.domain.models.TransferCommand
import com.circaso.wallet.domain.models.WalletOwner
import com.circaso.wallet.domain.models.WalletQuery
import com.circaso.wallet.domain.ports.Transactor
import com.circaso.wallet.domain.repositories.FeeLedgerRepository
import com.circaso.wallet.domain.repositories.FeeQuoteRepository
import com.circaso.wallet.domain.repositories.RuleRepository
import com.circaso.wallet.domain.repositories.TransactionRepository
import com.circaso.wallet.domain.repositories.TransferRepository
import com.circaso.wallet.domain.repositories.WalletOutbox
import com.circaso.wallet.domain.repositories.WalletRepository
import com.circaso.wallet.domain.support.Counterparties
import com.circaso.wallet.domain.support.CustodyUpdates
import com.circaso.wallet.domain.support.FeeCalculator
import com.circaso.wallet.domain.support.FeeQuotes
import com.circaso.wallet.domain.support.FeeSettlement
import com.circaso.wallet.domain.support.HolderFrames
import com.circaso.wallet.domain.support.Holders
import com.circaso.wallet.domain.support.HoldingPrices
import com.circaso.wallet.domain.support.HoldingsReader
import com.circaso.wallet.domain.support.IncomingUpdates
import com.circaso.wallet.domain.support.Limits
import com.circaso.wallet.domain.support.OrderedIds
import com.circaso.wallet.domain.support.OutgoingUpdates
import com.circaso.wallet.domain.support.Payees
import com.circaso.wallet.domain.support.PaymentPlanner
import com.circaso.wallet.domain.support.Reconciler
import com.circaso.wallet.domain.support.SenderRules
import com.circaso.wallet.domain.support.SplitPayments
import com.circaso.wallet.domain.support.SupportAccountRule
import com.circaso.wallet.domain.support.TransferEvents
import com.circaso.wallet.domain.support.TransferQuoter
import com.circaso.wallet.domain.support.WalletCreation
import com.circaso.wallet.domain.support.WalletListings
import com.circaso.wallet.domain.support.WalletSync
import com.circaso.wallet.domain.usecases.AddChain
import com.circaso.wallet.domain.usecases.ApplyCustodyUpdate
import com.circaso.wallet.domain.usecases.CheckFundsBeforeDeletion
import com.circaso.wallet.domain.usecases.CheckRecipient
import com.circaso.wallet.domain.usecases.CheckStuckTransfers
import com.circaso.wallet.domain.usecases.CreateGroupWallet
import com.circaso.wallet.domain.usecases.CreateWallet
import com.circaso.wallet.domain.usecases.ExpireUnsignedTransfers
import com.circaso.wallet.domain.usecases.FindGroupWallet
import com.circaso.wallet.domain.usecases.GetGroupWallet
import com.circaso.wallet.domain.usecases.GetTransfer
import com.circaso.wallet.domain.usecases.GetWallet
import com.circaso.wallet.domain.usecases.HasWallet
import com.circaso.wallet.domain.usecases.ListChains
import com.circaso.wallet.domain.usecases.ListMyWallets
import com.circaso.wallet.domain.usecases.ListTransactions
import com.circaso.wallet.domain.usecases.LookupTransfer
import com.circaso.wallet.domain.usecases.OpenSession
import com.circaso.wallet.domain.usecases.PrepareTransfer
import com.circaso.wallet.domain.usecases.QuoteTransfer
import com.circaso.wallet.domain.usecases.ReconcileWallets
import com.circaso.wallet.domain.usecases.ReplayFrames
import com.circaso.wallet.domain.usecases.RequestSetup
import com.circaso.wallet.domain.usecases.SettleSplitPayments
import com.circaso.wallet.domain.usecases.ValidateAddress
import com.circaso.wallet.presentation.services.WalletDirectoryService
import java.math.BigInteger
import java.util.UUID

/** 500 USDC a payment, 1 000 a day, in minor units (the defaults of the settings). */
val TEST_LIMITS = TestLimits(perPaymentMinor = 500_000_000, dailyMinor = 1_000_000_000)

/** The personal placeholders the server seeds (V4), in USDC minor units. */
data class TestLimits(val perPaymentMinor: Long, val dailyMinor: Long)

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
    val ruleRows: RuleRepository = MemoryRules(),
    val quoteRows: FeeQuoteRepository = MemoryFeeQuotes(),
    val ledger: FeeLedgerRepository = MemoryFeeLedger(),
) {
    val custody = FakeCustody(chains)
    val rates = FakeRates()
    val prices = FakePrices()
    val bus = MemoryBus()
    val cache = MemoryBalanceCache()
    val adding = MemoryAddingChains()
    val clock = MutableClock()
    val frames = RecordingFrames()
    val reminders = MemoryReminders()
    val holds = MemoryHolds()
    val accounts = FakeAccounts()
    val reach = FakeReach()
    val chats = FakeChats()
    val profiles = FakeProfiles()
    val ids = OrderedIds(clock)
    val active = MemoryActiveWallets(clock)
    val notices = MemoryStuckNotices()
    val setupChecks = MemorySetupChecks(now = { clock.instant() })
    val pendingSetups = MemoryPendingSetups(clock)
    val jobs = JobSettings()

    val owners = MemoryWalletOwners()
    val approvals = FakePayoutApprovals()
    val spaces = FakeSpaces()
    val holders = Holders(owners, chats, spaces)

    /** What reached people: [frames] records them per person, after [HolderFrames] fans a group wallet's out. */
    private val sent = HolderFrames(holders, chats, frames, spaces)
    val sync = WalletSync(wallets, transactor, outbox, bus, custody, adding, sent, clock, owners)
    val progress =
        com.circaso.wallet.domain.support
            .SetupProgress(wallets, sync, setupChecks, pendingSetups)
    val warnings = RecordingWarnings()
    private val holdings = HoldingsReader(custody, cache, warnings)
    private val support = SupportAccountRule(accounts)
    private val payees = Payees(wallets, owners, reach, chats, custody, support, spaces)
    private val listings = WalletListings(wallets, payees)
    private val events = TransferEvents(bus)
    val rules = SenderRules(wallets, accounts, holds, holdings, custody, support)
    val feeConfig = FakeFeeConfig()
    val limits = Limits(ruleRows, transfers, clock)
    val fees = FeeCalculator(ruleRows, custody, feeConfig, wallets, owners, warnings, clock)
    private val planner = PaymentPlanner(rules, payees, fees, limits, spaces)
    private val quotes = FeeQuotes(quoteRows, clock)
    val quoter = TransferQuoter(holders, planner, quotes, clock)
    val quoteTransfer = QuoteTransfer(quoter)

    val getWallet =
        GetWallet(
            wallets,
            sync,
            adding,
            holdings,
            rates,
            custody,
            active,
            holders,
            progress,
            HoldingPrices(prices, custody),
        )
    private val creation = WalletCreation(wallets, transactor, custody, sync, clock, pendingSetups)
    val createWallet = CreateWallet(creation, support)
    val createGroupWallet = CreateGroupWallet(chats, owners, accounts, support, creation, clock)
    val getGroupWallet = GetGroupWallet(chats, owners, listings, progress)
    val listMyWallets = ListMyWallets(chats, owners, listings, spaces)
    val createSpaceWallet =
        com.circaso.wallet.domain.usecases
            .CreateSpaceWallet(owners, accounts, support, creation, listings, spaces, clock)
    val getSpaceWallet =
        com.circaso.wallet.domain.usecases
            .GetSpaceWallet(owners, listings, spaces, progress)
    val findSpaceWallet =
        com.circaso.wallet.domain.usecases
            .FindSpaceWallet(owners, listings)
    val findGroupWallet = FindGroupWallet(owners, listings)
    val openSession = OpenSession(wallets, custody, active, holders)
    val listChains = ListChains(wallets, sync, adding, custody, holders)
    val addChain = AddChain(wallets, sync, adding, custody, support, clock, holders)
    val addMissingChains =
        com.circaso.wallet.domain.usecases
            .AddMissingChains(wallets, sync, adding, custody, support, clock, holders)
    val checkFunds = CheckFundsBeforeDeletion(wallets, sync, holdings, owners)
    val hasWallet = HasWallet(wallets)
    val prepareTransfer =
        PrepareTransfer(
            transfers,
            transactions,
            wallets,
            transactor,
            planner,
            limits,
            quotes,
            ledger,
            custody,
            outbox,
            bus,
            events,
            ids,
            clock,
            holders,
            approvals,
        )
    val getTransfer = GetTransfer(transfers, chats, spaces)
    val listTransactions = ListTransactions(transactions, Counterparties(profiles, wallets, owners), rates, holders)
    val checkRecipient = CheckRecipient(payees, custody, support)
    val requestSetup = RequestSetup(payees, reminders, profiles, sent, support)
    val validateAddress = ValidateAddress(custody)
    val outgoing =
        OutgoingUpdates(
            transfers,
            transactions,
            transactor,
            outbox,
            bus,
            events,
            sent,
            FeeSettlement(ledger, feeConfig, warnings),
            warnings,
            ids,
        )
    val updates =
        CustodyUpdates(
            wallets,
            sync,
            outgoing,
            IncomingUpdates(wallets, transactions, transactor, sent, profiles, ids, transfers),
            cache,
            warnings,
            clock,
            feeConfig,
            SplitPayments(transfers, custody, warnings, clock),
        )
    val applyUpdate = ApplyCustodyUpdate(updates)
    val expireUnsigned = ExpireUnsignedTransfers(transfers, outgoing, jobs, clock)
    val checkStuck =
        CheckStuckTransfers(transfers, wallets, custody, updates, notices, warnings, jobs, clock)
    val reconcileWallets =
        ReconcileWallets(active, transactions, Reconciler(wallets, custody, updates, warnings), jobs, clock)
    val settleSplits = SettleSplitPayments(transfers, wallets, custody, updates, warnings, jobs, clock)
    val sweepSetups =
        com.circaso.wallet.domain.usecases
            .SweepWalletSetups(progress, clock)

    val directory =
        WalletDirectoryService(
            hasWallet,
            checkFunds,
            applyUpdate,
            ReplayFrames(NoFramesKept),
            LookupTransfer(transfers),
            findGroupWallet,
            findSpaceWallet,
            com.circaso.wallet.domain.usecases.CheckSpaceWalletFunds(
                owners,
                com.circaso.wallet.domain.support
                    .HeldFunds(wallets, sync, holdings),
            ),
            com.circaso.wallet.domain.usecases
                .ApplyWalletChallenge(progress),
            com.circaso.wallet.domain.usecases
                .FindDepositAddress(wallets),
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

    /** W17 for the same payment as [pay]. */
    suspend fun quote(
        sender: UserId,
        payee: Payee,
        usdcMinor: Long,
        blockchain: String = TEST_CHAINS.default,
        walletId: com.circaso.wallet.api.WalletId? = null,
    ) = quoteTransfer(
        TransferCommand(
            sender,
            blockchain,
            payee,
            Money(usdcMinor, Asset.USDC),
            null,
            null,
            UUID.randomUUID(),
            walletId,
        ),
    )

    /** What has left [holder]'s wallet (or may still, awaiting a signature) in its rolling window, fees included. */
    fun used(holder: UserId): Long =
        kotlinx.coroutines.runBlocking {
            val owner =
                owners.find(
                    com.circaso.wallet.api
                        .WalletId(holder.value),
                ) ?: WalletOwner.Person(holder)
            limits.allowance(owner, Asset.USDC).usedMinor
        }

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

/** The access rules with no group wallets anywhere: everyone sees only their own wallet. */
fun personalHolders() = Holders(MemoryWalletOwners(), FakeChats())
