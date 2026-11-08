package com.circaso.share

import com.circaso.chama.api.ChamaStatement
import com.circaso.chama.api.ChamaStatementLine
import com.circaso.chama.api.Chamas
import com.circaso.integrations.api.ExchangeRates
import com.circaso.integrations.api.UsdRate
import com.circaso.messaging.api.ChatDirectory
import com.circaso.messaging.api.ChatId
import com.circaso.share.api.ShareCode
import com.circaso.share.domain.models.LinkCursor
import com.circaso.share.domain.models.ShareContent
import com.circaso.share.domain.models.ShareLink
import com.circaso.share.domain.ports.ViewThrottle
import com.circaso.share.domain.repositories.LinkRepository
import com.circaso.share.domain.support.ShareConfig
import com.circaso.share.domain.support.Snapshots
import com.circaso.share.domain.usecases.CreateShareLink
import com.circaso.share.domain.usecases.ListShareLinks
import com.circaso.share.domain.usecases.OpenShareLink
import com.circaso.share.domain.usecases.RemoveAccountLinks
import com.circaso.share.domain.usecases.RevokeShareLink
import com.circaso.share.domain.usecases.ViewSharePage
import com.circaso.share.presentation.services.IdentityEventsService
import com.circaso.share.presentation.services.SharePageService
import com.circaso.share.presentation.services.ShareService
import com.circaso.shared.UserId
import com.circaso.users.api.Invites
import com.circaso.users.api.IssuedInvite
import com.circaso.users.api.Profiles
import com.circaso.users.api.PublicProfile
import com.circaso.wallet.api.GroupWalletSummary
import com.circaso.wallet.api.TransferId
import com.circaso.wallet.api.TransferSummary
import com.circaso.wallet.api.WalletAmount
import com.circaso.wallet.api.Wallets
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

/** A clock the test moves. */
class TestClock(var now: Instant = Instant.parse("2026-11-06T10:00:00Z")) : Clock() {
    override fun getZone() = ZoneOffset.UTC

    override fun withZone(zone: java.time.ZoneId?) = this

    override fun instant(): Instant = now
}

/** `share.links` in memory, with the same rules as the PostgreSQL one. */
class MemoryLinks : LinkRepository {
    private class Row(var link: ShareLink, var expiresAt: Instant?)

    private val rows = LinkedHashMap<String, Row>()

    override suspend fun insert(link: ShareLink, expiresAt: Instant?): Boolean {
        if (link.code.value in rows) return false
        rows[link.code.value] = Row(link, expiresAt)
        return true
    }

    override suspend fun find(code: ShareCode, now: Instant): ShareLink? =
        rows[code.value]?.takeIf { it.expiresAt == null || it.expiresAt!!.isAfter(now) }?.link

    override suspend fun listLive(
        ownerId: UserId,
        now: Instant,
        after: LinkCursor?,
        limit: Int,
    ): List<ShareLink> =
        rows.values
            .map { it.link }
            .filter { it.ownerId == ownerId && it.isLive(now) }
            .sortedWith(compareByDescending<ShareLink> { it.createdAt }.thenByDescending { it.code.value })
            .filter {
                after == null || it.createdAt < after.createdAt ||
                    (it.createdAt == after.createdAt && it.code.value < after.code.value)
            }.take(limit)

    override suspend fun revoke(
        code: ShareCode,
        ownerId: UserId,
        now: Instant,
        keepUntil: Instant,
    ): Boolean {
        val row = rows[code.value] ?: return false
        if (row.link.ownerId != ownerId || !row.link.isLive(now)) return false
        row.link = row.link.copy(content = null, revokedAt = now)
        row.expiresAt = keepUntil
        return true
    }

    override suspend fun updateContent(code: ShareCode, content: ShareContent): Boolean {
        val row = rows[code.value] ?: return false
        if (row.link.revokedAt != null || row.link.content == null) return false
        row.link = row.link.copy(content = content)
        return true
    }

    override suspend fun deleteOwnedBy(ownerId: UserId): Int {
        val gone = rows.filterValues { it.link.ownerId == ownerId }.keys
        gone.forEach(rows::remove)
        return gone.size
    }

    fun count() = rows.size
}

class CountingThrottle(var max: Long = Long.MAX_VALUE) : ViewThrottle {
    val seen = mutableMapOf<String, Long>()

    override suspend fun take(
        address: String,
        max: Long,
        window: Duration,
    ): Long? {
        val n = (seen[address] ?: 0) + 1
        seen[address] = n
        return if (n > minOf(max, this.max)) 42 else null
    }
}

class FakeWallets : Wallets {
    val transfers = mutableMapOf<TransferId, TransferSummary>()
    val addresses = mutableMapOf<Pair<UserId, String>, String>()

    override suspend fun hasWallet(userId: UserId): Boolean = true

    override suspend fun transfer(transferId: TransferId): TransferSummary? = transfers[transferId]

    override suspend fun groupWallet(chatId: ChatId): GroupWalletSummary? = null

    override suspend fun depositAddress(userId: UserId, blockchain: String): String? =
        addresses[userId to blockchain.uppercase()]
}

class FakeChamas : Chamas {
    val statements = mutableMapOf<Pair<ChatId, UserId>, ChamaStatement>()

    override suspend fun hasGoal(chatId: ChatId): Boolean = statements.keys.any { it.first == chatId }

    override suspend fun statement(
        chatId: ChatId,
        viewer: UserId,
        lines: Int,
    ): ChamaStatement? = statements[chatId to viewer]?.let { it.copy(lines = it.lines.take(lines)) }
}

class FakeProfiles : Profiles {
    val people = mutableMapOf<UserId, PublicProfile>()

    fun add(
        id: UserId,
        name: String,
        handle: String? = null,
    ) {
        people[id] = PublicProfile(id, name, handle, null, null)
    }

    override suspend fun get(ids: Set<UserId>): Map<UserId, PublicProfile> = people.filterKeys { it in ids }
}

class FakeInvites(private val clock: Clock) : Invites {
    var available = true
    val made = mutableListOf<UserId>()

    override suspend fun create(owner: UserId): IssuedInvite? {
        if (!available) return null
        made += owner
        return IssuedInvite("invite-token-${made.size}", clock.instant().plus(Duration.ofDays(7)))
    }
}

class FakeRates : ExchangeRates {
    override suspend fun usdRate(currency: String): UsdRate? =
        if (currency == "KES") UsdRate("KES", BigDecimal("129.00"), Instant.parse("2026-11-06T06:00:00Z")) else null
}

class FakeChats : ChatDirectory {
    val members = mutableMapOf<ChatId, MutableSet<UserId>>()

    override suspend fun isMember(chatId: ChatId, userId: UserId): Boolean = members[chatId]?.contains(userId) == true

    override suspend fun members(chatId: ChatId): Set<UserId> = members[chatId].orEmpty()

    override suspend fun admins(chatId: ChatId): Set<UserId> = emptySet()

    override suspend fun isGroup(chatId: ChatId): Boolean = chatId in members

    override suspend fun groupsOf(userId: UserId): List<ChatId> = members.filterValues { userId in it }.keys.toList()

    override suspend fun owner(chatId: ChatId): UserId? = null
}

/** The module wired over fakes, with a few people, a payment and a group. */
class ShareHarness {
    val clock = TestClock()
    val links = MemoryLinks()
    val throttle = CountingThrottle()
    val wallets = FakeWallets()
    val chamas = FakeChamas()
    val profiles = FakeProfiles()
    val invites = FakeInvites(clock)
    val chats = FakeChats()
    val config = ShareConfig(baseUrl = "https://sandbox.circaso.com", androidCertFingerprints = listOf("AA:BB"))

    val wanjiru = UserId.new()
    val juma = UserId.new()
    val stranger = UserId.new()
    val group = ChatId.new()

    private val snapshots = Snapshots(wallets, chamas, profiles, invites, FakeRates(), clock)
    val shares =
        ShareService(
            CreateShareLink(links, snapshots, config, clock),
            ListShareLinks(links, clock),
            RevokeShareLink(links, config, clock),
            OpenShareLink(links, snapshots, wallets, chats, clock),
        )
    val pages = SharePageService(ViewSharePage(links, snapshots, throttle, config, clock))
    val identity = IdentityEventsService(RemoveAccountLinks(links))

    init {
        profiles.add(wanjiru, "Wanjiru", "wanjiru")
        profiles.add(juma, "Juma")
        chats.members[group] = mutableSetOf(wanjiru, juma)
        chamas.statements[group to wanjiru] =
            ChamaStatement(
                goalName = "Holiday Fund",
                targetMinor = 500_000_000,
                contributedMinor = 125_500_000,
                paidOutMinor = 20_000_000,
                heldMinor = 105_500_000,
                memberCount = 2,
                deadline = Instant.parse("2026-12-20T00:00:00Z"),
                lines =
                    listOf(
                        ChamaStatementLine(clock.now, false, 20_000_000, null, "Mama Mboga", "Food for the trip"),
                        ChamaStatementLine(clock.now.minusSeconds(60), true, 125_500_000, "Juma", null, null),
                    ),
            )
        wallets.addresses[wanjiru to "BASE-SEPOLIA"] = "0x1234567890abcdef1234567890abcdef12345678"
    }

    fun payment(
        state: String = "CONFIRMED",
        from: UserId = wanjiru,
        to: UserId? = juma,
        txHash: String? = "0xfeedbeef00112233445566778899aabbccddeeff00112233445566778899aabb",
    ): TransferId {
        val id = TransferId.new()
        wallets.transfers[id] =
            TransferSummary(
                transferId = id,
                senderId = from,
                recipientId = to,
                destinationAddress = "0x9999999999999999999999999999999999999999",
                amount = WalletAmount("USDC", "12500000", "12.5"),
                state = state,
                chatId = null,
                signerId = from,
                blockchain = "BASE-SEPOLIA",
                txHash = txHash,
                createdAt = clock.now.minusSeconds(120),
                updatedAt = clock.now.minusSeconds(60),
            )
        return id
    }
}
