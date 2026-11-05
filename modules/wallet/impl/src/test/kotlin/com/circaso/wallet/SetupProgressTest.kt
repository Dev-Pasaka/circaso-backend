package com.circaso.wallet

import com.circaso.integrations.api.CustodyChallenge
import com.circaso.integrations.api.CustodyChallengeKind
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import com.circaso.shared.decode
import com.circaso.wallet.api.SpaceAccess
import com.circaso.wallet.api.WalletCreated
import com.circaso.wallet.domain.models.CreateGroupWalletCommand
import com.circaso.wallet.domain.models.CreateSpaceWalletCommand
import com.circaso.wallet.domain.models.WalletStatus
import com.circaso.wallet.domain.ports.SETUP_CHECK_WINDOW
import com.circaso.wallet.domain.ports.WalletFrame
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A wallet being set up turns `ACTIVE` as soon as Circle has it (measured 2026-11-05: about a minute late, waiting for
 * a sweep): Circle's `challenges.initialize` webhook reads the user's wallets at once, and the setup-status reads the
 * app polls (W1, W15, W19) read Circle while setting up, at most once per window per wallet.
 */
class SetupProgressTest {
    private val h = Harness().apply { setupChecks.window = SETUP_CHECK_WINDOW }
    private val baraka = UserId.new()
    private val amina = UserId.new()
    private val space = UUID.randomUUID()

    init {
        h.spaces.roles[space to baraka] = SpaceAccess.OWNER
        h.spaces.roles[space to amina] = SpaceAccess.ADMIN
    }

    private suspend fun setUpSpace() =
        h.createSpaceWallet(CreateSpaceWalletCommand(baraka, space, UUID.randomUUID())).data().owner

    private fun spaceStatus(by: UserId = baraka) =
        runBlocking {
            h
                .getSpaceWallet(by, space)
                .data()
                .listing
                ?.status
        }

    private fun created() =
        h.bus.published
            .filter { it.type == WalletCreated.TYPE }
            .map { it.decode<WalletCreated>() }

    @Test
    fun `W19 sees the space wallet turn active on the first poll after the PIN, and the space goes live once`() =
        runBlocking<Unit> {
            val owner = setUpSpace()
            h.clock.advance(Duration.ofSeconds(3))
            assertEquals(WalletStatus.SETTING_UP, spaceStatus(), "no PIN yet: Circle has nothing")

            h.custody.sign(h.circleUserOf(owner.holder))
            h.clock.advance(Duration.ofSeconds(3))
            assertEquals(WalletStatus.ACTIVE, spaceStatus())
            assertEquals(WalletStatus.ACTIVE, spaceStatus(by = amina))

            assertEquals(
                listOf(space.toString()),
                created().map { it.spaceId },
                "one WalletCreated: the space goes live",
            )
            val ready =
                h.frames.sent
                    .filter { it.second is WalletFrame.WalletReady }
                    .map { it.first }
                    .toSet()
            assertEquals(setOf(baraka, amina), ready)
        }

    @Test
    fun `polls inside the window share one Circle read, and an active wallet never asks Circle`() =
        runBlocking<Unit> {
            val owner = setUpSpace()
            h.clock.advance(Duration.ofSeconds(3))
            val before = h.custody.count("wallets")

            repeat(5) { spaceStatus() }
            spaceStatus(by = amina)
            assertEquals(before + 1, h.custody.count("wallets"), "six polls in one window, one read of Circle")

            h.clock.advance(Duration.ofSeconds(3))
            spaceStatus()
            assertEquals(before + 2, h.custody.count("wallets"), "the next 3-second poll reads Circle again")

            h.custody.sign(h.circleUserOf(owner.holder))
            h.clock.advance(Duration.ofSeconds(3))
            assertEquals(WalletStatus.ACTIVE, spaceStatus())
            val active = h.custody.count("wallets")
            h.clock.advance(Duration.ofSeconds(3))
            repeat(3) { spaceStatus() }
            assertEquals(active, h.custody.count("wallets"), "an active wallet is read from our rows only")
        }

    @Test
    fun `Circle being down while setting up leaves the poll answering from our rows`() =
        runBlocking<Unit> {
            setUpSpace()
            h.custody.down = true
            h.clock.advance(Duration.ofSeconds(3))
            assertEquals(WalletStatus.SETTING_UP, spaceStatus())
        }

    @Test
    fun `W15 sees the group wallet turn active on the first poll after the PIN`() =
        runBlocking<Unit> {
            val otieno = UserId.new()
            val chat = h.chats.group(listOf(amina, otieno, UserId.new()))
            val owner = h.createGroupWallet(CreateGroupWalletCommand(amina, chat, UUID.randomUUID())).data().owner
            h.custody.sign(h.circleUserOf(owner.holder))
            h.clock.advance(Duration.ofSeconds(3))

            val listing = h.getGroupWallet(otieno, chat).data().listing
            assertEquals(WalletStatus.ACTIVE, listing?.status)
            assertEquals(chat.toString(), created().single().groupId?.toString())
        }

    @Test
    fun `W1 polling a personal wallet being set up reads Circle at most once per window`() =
        runBlocking<Unit> {
            h.create(baraka).data()
            h.clock.advance(Duration.ofSeconds(3))
            val before = h.custody.count("wallets")
            repeat(4) { assertEquals(WalletStatus.SETTING_UP, h.wallet(baraka).data().status) }
            assertEquals(before + 1, h.custody.count("wallets"))

            h.custody.sign(h.circleUserOf(baraka))
            h.clock.advance(Duration.ofSeconds(3))
            assertEquals(WalletStatus.ACTIVE, h.wallet(baraka).data().status)
        }

    @Test
    fun `Circle's challenge webhook records the new wallet at once, and a second delivery changes nothing`() =
        runBlocking<Unit> {
            val owner = setUpSpace()
            val circleUserId = h.circleUserOf(owner.holder)
            h.custody.sign(circleUserId)
            val challenge = CustodyChallenge(circleUserId, "challenge-1", CustodyChallengeKind.SETUP)

            assertIs<Resource.Success<Unit>>(h.directory.onWalletChallenge(challenge))
            assertEquals(1, h.wallets.wallets(owner.holder).size)
            assertEquals(listOf(space.toString()), created().map { it.spaceId })
            assertTrue(h.frames.sent.any { it.first == baraka && it.second is WalletFrame.WalletReady })

            assertIs<Resource.Success<Unit>>(h.directory.onWalletChallenge(challenge))
            assertEquals(1, created().size, "Circle delivering again announces nothing new")

            val polls = h.custody.count("wallets")
            spaceStatus()
            assertEquals(polls, h.custody.count("wallets"), "the poll after the webhook reads our rows only")
            assertEquals(WalletStatus.ACTIVE, spaceStatus())
        }

    @Test
    fun `a challenge for someone else's Circle user is acknowledged, one while Circle is down is delivered again`() =
        runBlocking<Unit> {
            val stranger = CustodyChallenge(UUID.randomUUID().toString(), "c-1", CustodyChallengeKind.SETUP)
            val unreadable = CustodyChallenge("not-a-uuid", "c-2", CustodyChallengeKind.CREATE_WALLET)
            val before = h.custody.count("wallets")
            assertIs<Resource.Success<Unit>>(h.directory.onWalletChallenge(stranger))
            assertIs<Resource.Success<Unit>>(h.directory.onWalletChallenge(unreadable))
            assertEquals(before, h.custody.count("wallets"), "not ours: Circle isn't asked")

            val owner = setUpSpace()
            h.custody.down = true
            val ours = CustodyChallenge(h.circleUserOf(owner.holder), "c-3", CustodyChallengeKind.SETUP)
            assertIs<Resource.Error>(h.directory.onWalletChallenge(ours))
        }

    @Test
    fun `asking again while the PIN challenge is open hands back the same challenge, not a second initialize`() =
        runBlocking<Unit> {
            val first = h.createSpaceWallet(CreateSpaceWalletCommand(baraka, space, UUID.randomUUID())).data()
            val again = h.createSpaceWallet(CreateSpaceWalletCommand(baraka, space, UUID.randomUUID())).data()
            assertEquals(first.setup.challengeId, again.setup.challengeId)
            assertEquals(1, h.custody.count("createWallet"), "one user/initialize")
            assertTrue(
                again.setup.sdk.userToken
                    .isNotEmpty(),
                "with a current SDK session to run it",
            )

            h.custody.sign(h.circleUserOf(first.owner.holder))
            assertEquals(
                "wallet.already_exists",
                h.createSpaceWallet(CreateSpaceWalletCommand(baraka, space, UUID.randomUUID())).code(),
                "the PIN went through: Circle's wallets are read first and recorded",
            )
            assertEquals(1, created().size)
        }

    @Test
    fun `a challenge Circle no longer has open, or that failed, is replaced by a fresh one`() =
        runBlocking<Unit> {
            val first = h.create(baraka).data()
            h.custody.openChallenges.clear() // expired at Circle
            val second = h.create(baraka).data()
            assertTrue(first.challengeId != second.challengeId)
            assertEquals(2, h.custody.count("createWallet"))

            val failed =
                CustodyChallenge(h.circleUserOf(baraka), second.challengeId!!, CustodyChallengeKind.SETUP, false)
            assertIs<Resource.Success<Unit>>(h.directory.onWalletChallenge(failed))
            val third = h.create(baraka).data()
            assertTrue(third.challengeId != second.challengeId, "a failed challenge isn't handed out again")
        }

    @Test
    fun `the setup sweep records a wallet whose webhook was lost while nobody polls, for 30 minutes`() =
        runBlocking<Unit> {
            val owner = setUpSpace()
            assertEquals(1, h.sweepSetups(), "setting up: read from Circle, nothing there yet")
            assertTrue(created().isEmpty())

            h.custody.sign(h.circleUserOf(owner.holder))
            h.clock.advance(Duration.ofSeconds(30))
            assertEquals(1, h.sweepSetups())
            assertEquals(listOf(space.toString()), created().map { it.spaceId }, "the space goes live")
            assertEquals(0, h.sweepSetups(), "recorded: off the list")

            h.create(amina).data()
            h.clock.advance(Duration.ofMinutes(31))
            assertEquals(0, h.sweepSetups(), "a setup older than 30 minutes is left alone")
        }
}
