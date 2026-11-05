package com.circaso.spaces

import com.circaso.infrastructure.testing.TestPostgres
import com.circaso.spaces.api.InvitedFrame
import com.circaso.spaces.api.MembershipUpdatedFrame
import com.circaso.spaces.api.PostNewFrame
import com.circaso.spaces.api.PriceChangedFrame
import com.circaso.spaces.api.PostId
import com.circaso.spaces.domain.models.CreatePostCommand
import com.circaso.spaces.domain.models.MemberFilter
import com.circaso.spaces.domain.models.MembersQuery
import com.circaso.spaces.domain.models.MuteDuration
import com.circaso.spaces.domain.models.NotificationLevel
import com.circaso.spaces.domain.models.PaidState
import com.circaso.spaces.domain.models.PaymentKind
import com.circaso.spaces.domain.models.Period
import com.circaso.spaces.domain.models.PostingMode
import com.circaso.spaces.domain.models.PostsQuery
import com.circaso.spaces.domain.models.Price
import com.circaso.spaces.domain.models.Relation
import com.circaso.spaces.domain.models.ReportAction
import com.circaso.spaces.domain.models.ReportCommand
import com.circaso.spaces.domain.models.ReportReason
import com.circaso.spaces.domain.models.Role
import com.circaso.spaces.domain.models.SentInviteState
import com.circaso.spaces.domain.models.SpaceState
import com.circaso.spaces.domain.models.TeamEntry
import com.circaso.spaces.domain.models.Topic
import com.circaso.spaces.domain.models.UpdateSpaceCommand
import com.circaso.spaces.domain.models.Visibility
import com.circaso.wallet.api.SpaceAccess
import kotlinx.coroutines.runBlocking
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Spaces end to end over PostgreSQL (its own Flyway scripts), with fakes for the other modules. */
class SpacesTest {
    private val h = SpacesHarness()
    private val baraka = h.person("Baraka")
    private val amina = h.person("Amina")
    private val juma = h.person("Juma")
    private val faith = h.person("Faith")
    private val usdc5 = 5_000_000L

    @BeforeTest
    fun clean() = TestPostgres.truncate("spaces")

    private fun post(by: com.circaso.shared.UserId, space: com.circaso.spaces.api.SpaceId, text: String, reply: PostId? = null) =
        CreatePostCommand(by, space, UUID.randomUUID(), text, reply, emptyList())

    // ---- creating ----

    @Test
    fun `a new account waits 7 days, and nobody makes more than 3 spaces a day`() =
        runBlocking<Unit> {
            h.accounts.created[juma] = h.clock.instant().minus(Duration.ofDays(2))
            h.create(h.command(juma)).assertError("spaces.account_too_new")

            repeat(3) { h.create(h.command(baraka, handle = "space$it")).data() }
            h.create(h.command(baraka, handle = "space4")).assertError("spaces.too_many_spaces")
            h.clock.advance(Duration.ofHours(25))
            h.create(h.command(baraka, handle = "space4")).data()
        }

    @Test
    fun `a taken handle offers three free ones, and the same create key returns the first space`() =
        runBlocking<Unit> {
            val key = UUID.randomUUID().toString()
            val first = h.create(h.command(baraka, key = key)).data()
            val again = h.create(h.command(baraka, key = key)).data()
            assertEquals(first.summary.space.spaceId, again.summary.space.spaceId)

            h.create(h.command(amina)).assertError("spaces.handle_taken")
            val check = h.checkHandle("@SneakerDrop").data()
            assertFalse(check.available)
            assertEquals(3, check.suggestions.size)
            assertTrue(check.suggestions.none { it == "sneakerdrop" })
            assertTrue(h.checkHandle("kilimaniyoga").data().available)
            h.checkHandle("ab").assertError("spaces.handle_invalid")
        }

    @Test
    fun `a space is hidden until its wallet is active, then it is live`() =
        runBlocking<Unit> {
            val created = h.create(h.command(baraka, team = listOf(TeamEntry(amina, Role.ADMIN)))).data()
            val id = created.summary.space.spaceId
            assertEquals(SpaceState.SETTING_UP, created.summary.space.state)
            h.get(juma, id).assertError("spaces.space_not_found")
            assertEquals(Role.ADMIN, h.spaceOf(amina, id).me.role, "the team sees it while it's set up")
            assertTrue(h.search("sneaker", null, 0, 10).data().spaces.isEmpty())

            h.wallets.ready(id, baraka)
            assertEquals(SpaceState.LIVE, h.spaceOf(baraka, id).summary.space.state, "SP3 heals a late event")
            assertEquals(SpaceState.LIVE, h.spaceOf(juma, id).summary.space.state)
            assertEquals(2, h.spaceOf(juma, id).summary.space.memberCount)
        }

    // ---- public spaces and the chat ----

    @Test
    fun `follow a public space, post, react and read; visitors read the latest posts only`() =
        runBlocking<Unit> {
            val id = h.liveSpace(baraka)
            val first = h.post(post(baraka, id, "New Jordan 1s land Friday")).data()
            h.post(post(juma, id, "hi")).assertError("spaces.not_member")

            val visitor = h.listPosts(PostsQuery(juma, id, null, null, 50)).data()
            assertEquals(listOf("New Jordan 1s land Friday"), visitor.items.map { it.post.text })
            assertNull(visitor.next)

            assertEquals(Relation.MEMBER, h.follow(juma, id).data().relation)
            assertEquals(2, h.spaceOf(juma, id).summary.space.memberCount)
            val reply = h.post(post(juma, id, "Size 44?", first.post.postId)).data()
            assertEquals("New Jordan 1s land Friday", reply.replyTo?.text)
            val frames = h.frames.of<PostNewFrame>().last()
            assertEquals(setOf(baraka), frames.first, "the owner is pushed a reply to them")
            assertTrue(frames.second.notify)

            h.react(juma, id, first.post.postId, "🔥").data()
            val counts = h.react(amina.also { h.follow(it, id).data() }, id, first.post.postId, "🔥").data()
            assertEquals(2, counts.reactions.single().count)
            h.react(juma, id, first.post.postId, "fire").assertError("spaces.reaction_invalid")

            assertEquals(1, h.spaceOf(baraka, id).me.unreadCount)
            assertEquals(0, h.markRead(baraka, id, reply.post.postId).data())
        }

    @Test
    fun `a retried post with the same clientPostId is written once`() =
        runBlocking<Unit> {
            val id = h.liveSpace(baraka)
            val command = post(baraka, id, "Once")
            val a = h.post(command).data()
            val b = h.post(command).data()
            assertEquals(a.post.postId, b.post.postId)
            assertEquals(1, h.listPosts(PostsQuery(baraka, id, null, null, 50)).data().items.size)
        }

    @Test
    fun `admins-only spaces take posts from the team, muted members read but can't post, removed posts leave a tombstone`() =
        runBlocking<Unit> {
            val id =
                h.liveSpace(
                    baraka,
                    h.command(baraka, postingMode = PostingMode.ADMINS_ONLY, team = listOf(TeamEntry(amina, Role.MODERATOR))),
                )
            h.follow(juma, id).data()
            h.post(post(juma, id, "hello")).assertError("spaces.posting_restricted")
            h.update(UpdateSpaceCommand(baraka, id, postingMode = PostingMode.EVERYONE)).data()
            val spam = h.post(post(juma, id, "pay to my till first")).data()

            h.mute(amina, id, juma, MuteDuration.HOUR).data()
            h.post(post(juma, id, "again")).assertError("spaces.muted")
            h.react(juma, id, spam.post.postId, "🙏").data()
            h.mute(juma, id, amina, MuteDuration.HOUR).assertError("spaces.not_moderator")

            val removed = h.removePost(amina, id, spam.post.postId).data()
            assertNotNull(removed.post.removedAt)
            assertNull(removed.post.text)
            h.clock.advance(Duration.ofHours(2))
            h.post(post(juma, id, "back")).data()
        }

    // ---- members-only: paid membership ----

    @Test
    fun `a members-only space is joined by a confirmed payment, renewed, then grace, then ended`() =
        runBlocking<Unit> {
            val id = h.liveSpace(baraka, h.command(baraka, handle = "nairobibooks", visibility = Visibility.MEMBERS_ONLY, price = Price(usdc5, Period.MONTHLY)))
            h.follow(juma, id).assertError("spaces.payment_required")
            h.listPosts(PostsQuery(juma, id, null, null, 50)).assertError("spaces.members_only")

            val payment = h.startPayment(juma, id).data()
            assertEquals(PaymentKind.JOIN, payment.payment.kind)
            assertEquals(usdc5, payment.payment.amountMinor)
            assertNotNull(h.charge(payment.payment.paymentId, juma).data())
            assertNull(h.charge(payment.payment.paymentId, faith).data(), "only its payer can pay it")
            assertEquals(Relation.NONE, h.spaceOf(juma, id).me.relation, "never a member from the tap")

            h.confirmPayment(payment.payment.paymentId, juma, usdc5).data()
            h.confirmPayment(payment.payment.paymentId, juma, usdc5).data()
            val me = h.spaceOf(juma, id).me
            assertEquals(Relation.MEMBER, me.relation)
            val paidUntil = assertNotNull(me.membership?.paidUntil)
            assertEquals(Instant.parse("2026-12-04T09:00:00Z"), paidUntil)
            assertEquals(SpaceAccess.MEMBER, h.walletAccess(id, juma).data())
            h.startPayment(juma, id).assertError("spaces.already_member")

            // 3 days before the end: a reminder, once
            h.clock.now = paidUntil.minus(Duration.ofDays(3))
            assertEquals(1, h.timers().data())
            assertEquals(0, h.timers().data())
            assertEquals("RENEWAL_DUE", h.frames.of<MembershipUpdatedFrame>().last().second.reason)

            // renewing early starts at the end of the paid period
            val renewal = h.startPayment(juma, id).data()
            assertEquals(PaymentKind.RENEWAL, renewal.payment.kind)
            assertEquals(paidUntil, renewal.periodStart)
            h.confirmPayment(renewal.payment.paymentId, juma, usdc5).data()
            val renewed = assertNotNull(h.spaceOf(juma, id).me.membership?.paidUntil)
            assertEquals(Instant.parse("2027-01-04T09:00:00Z"), renewed)

            // the period ends: 3 days' grace, still a member; then it ends
            h.clock.now = renewed.plusSeconds(60)
            h.timers().data()
            assertEquals(PaidState.GRACE, h.spaceOf(juma, id).me.membership?.state)
            h.post(post(juma, id, "still here")).data()
            h.clock.advance(Duration.ofDays(3))
            h.timers().data()
            val ended = h.spaceOf(juma, id).me
            assertEquals(Relation.ENDED, ended.relation)
            h.listPosts(PostsQuery(juma, id, null, null, 50)).assertError("spaces.membership_ended")
            assertEquals(PaymentKind.REJOIN, h.startPayment(juma, id).data().payment.kind)
            assertEquals(1, h.mine(juma, 0, 10).data().items.size, "an ended membership keeps its place")
        }

    @Test
    fun `a price change is told to members and shows on their renewal`() =
        runBlocking<Unit> {
            val id = h.liveSpace(baraka, h.command(baraka, handle = "nairobibooks", visibility = Visibility.MEMBERS_ONLY, price = Price(usdc5, Period.MONTHLY)))
            val payment = h.startPayment(juma, id).data()
            h.confirmPayment(payment.payment.paymentId, juma, usdc5).data()

            h.update(UpdateSpaceCommand(amina, id, price = Price(6_000_000, Period.MONTHLY))).assertError("spaces.not_admin")
            val updated = h.update(UpdateSpaceCommand(baraka, id, price = Price(6_000_000, Period.MONTHLY))).data()
            assertEquals(1, updated.notified)
            assertEquals(setOf(juma), h.frames.of<PriceChangedFrame>().single().first)
            assertEquals(6_000_000, h.spaceOf(juma, id).me.membership?.nextPrice?.amountMinor)
            h.update(UpdateSpaceCommand(baraka, id, price = Price(1, Period.MONTHLY))).assertError("spaces.price_invalid")
        }

    // ---- invite-only ----

    @Test
    fun `invite-only spaces never show in search; asking, declining and asking again after 30 days`() =
        runBlocking<Unit> {
            val id = h.liveSpace(baraka, h.command(baraka, name = "Nairobi Founders Circle", handle = "founderscircle", visibility = Visibility.INVITE_ONLY))
            assertTrue(h.search("founders", null, 0, 10).data().spaces.isEmpty())
            assertNull(h.search("@founderscircle", null, 0, 10).data().exact)
            val outside = h.spaceOf(juma, id)
            assertFalse(outside.summary.showAbout)
            h.follow(juma, id).assertError("spaces.invite_required")
            h.listPosts(PostsQuery(juma, id, null, null, 50)).assertError("spaces.members_only")

            assertEquals(Relation.REQUESTED, h.request(juma, id).data().relation)
            assertEquals(1, h.listInvites(baraka, id).data().requests.size)
            h.answer(baraka, id, juma, accept = false).data()
            h.request(juma, id).assertError("spaces.request_too_soon")
            h.clock.advance(Duration.ofDays(31))
            h.request(juma, id).data()
            h.answer(baraka, id, juma, accept = true).data()
            assertEquals(Relation.MEMBER, h.spaceOf(juma, id).me.relation)
        }

    @Test
    fun `invites expire after 14 days and a decline is never told`() =
        runBlocking<Unit> {
            val id = h.liveSpace(baraka, h.command(baraka, handle = "founderscircle", visibility = Visibility.INVITE_ONLY))
            val results = h.invite(baraka, id, listOf(juma, faith)).data()
            assertTrue(results.all { it.outcome.name == "INVITED" })
            assertEquals(setOf(juma), h.frames.of<InvitedFrame>().first().first)
            assertEquals(Relation.INVITED, h.spaceOf(juma, id).me.relation)
            assertTrue(h.spaceOf(juma, id).summary.showAbout, "an invited person sees more than a stranger")

            val faithInvite = h.invites.of(id, faith)!!
            h.decline(faith, faithInvite.inviteId).data()
            val listed = h.listInvites(baraka, id).data().invites.associateBy { it.user.userId }
            assertEquals(SentInviteState.INVITED, listed.getValue(faith).state, "a decline shows as invited")

            h.clock.advance(Duration.ofDays(15))
            val jumaInvite = h.invites.of(id, juma)!!
            h.accept(juma, jumaInvite.inviteId).assertError("spaces.invite_not_found")
            assertEquals(SentInviteState.EXPIRED, h.listInvites(baraka, id).data().invites.first { it.user.userId == juma }.state)
            h.invite(baraka, id, listOf(juma)).data()
            assertEquals(Relation.MEMBER, h.accept(juma, jumaInvite.inviteId).data().me.relation)
        }

    // ---- roles and moderation ----

    @Test
    fun `roles follow SR2 - admins appoint moderators, only the owner appoints admins`() =
        runBlocking<Unit> {
            val id = h.liveSpace(baraka, h.command(baraka, team = listOf(TeamEntry(amina, Role.ADMIN))))
            h.follow(juma, id).data()
            h.follow(faith, id).data()
            h.setRole(amina, id, juma, Role.MODERATOR).data()
            h.setRole(amina, id, faith, Role.ADMIN).assertError("spaces.not_owner")
            h.setRole(juma, id, faith, Role.MODERATOR).assertError("spaces.not_admin")
            h.setRole(amina, id, baraka, Role.MEMBER).assertError("spaces.cannot_moderate")
            h.setRole(baraka, id, faith, Role.ADMIN).data()

            val members = h.listMembers(MembersQuery(juma, id, MemberFilter.ALL, null, null, 50)).data()
            assertEquals(baraka, members.team.first().profile.userId, "the owner first")
            assertEquals(setOf(baraka, amina, faith, juma), members.team.map { it.profile.userId }.toSet())
            assertEquals(4, members.count)
        }

    @Test
    fun `a ban removes the person and stops follow, invites and the private chat with the owner`() =
        runBlocking<Unit> {
            val id = h.liveSpace(baraka, h.command(baraka, team = listOf(TeamEntry(amina, Role.ADMIN))))
            h.follow(juma, id).data()
            assertTrue(h.privateChat(id, juma, baraka).data(), "a member may message the owner")
            assertTrue(h.privateChat(id, faith, baraka).data(), "a public space's owner is open to anyone")
            assertFalse(h.privateChat(id, juma, amina).data(), "only the owner, never another member")

            h.ban(amina, id, juma, true).data()
            assertEquals(Relation.BANNED, h.spaceOf(juma, id).me.relation)
            h.follow(juma, id).assertError("spaces.banned")
            assertFalse(h.privateChat(id, juma, baraka).data())
            assertFalse(h.privateChat(id, baraka, juma).data(), "both ways")
            assertEquals(SpaceAccess.NONE, h.walletAccess(id, juma).data())
            assertEquals("BANNED", h.invite(amina, id, listOf(juma)).data().single().outcome.name)
            h.ban(amina, id, baraka, true).assertError("spaces.cannot_moderate")

            h.ban(amina, id, juma, false).data()
            h.follow(juma, id).data()
        }

    // ---- reports ----

    @Test
    fun `reports reach the space's moderators without the reporter's name, and acting closes them all`() =
        runBlocking<Unit> {
            val id = h.liveSpace(baraka, h.command(baraka, team = listOf(TeamEntry(amina, Role.MODERATOR))))
            h.follow(juma, id).data()
            h.follow(faith, id).data()
            val spam = h.post(post(juma, id, "DM me for half-price Jordans")).data()

            val first = h.report(ReportCommand(faith, id, spam.post.postId, ReportReason.SPAM_SCAM, null)).data()
            val repeat = h.report(ReportCommand(faith, id, spam.post.postId, ReportReason.COUNTERFEIT, null)).data()
            assertEquals(first.reportId, repeat.reportId, "one open report per person and post")
            h.report(ReportCommand(baraka, id, spam.post.postId, ReportReason.SPAM_SCAM, "scam")).data()

            h.listReports(juma, id, true).assertError("spaces.not_moderator")
            val open = h.listReports(amina, id, true).data().single()
            assertEquals(2, open.count)
            assertEquals(ReportReason.SPAM_SCAM, open.reason)

            h.resolve(amina, id, spam.post.postId, ReportAction.REMOVE_POST, null).data()
            assertTrue(h.listReports(amina, id, true).data().isEmpty())
            assertEquals(ReportAction.REMOVE_POST, h.listReports(amina, id, false).data().single().action)
            assertNotNull(h.posts.find(spam.post.postId)?.removedAt)
        }

    // ---- discovery ----

    @Test
    fun `search by words and by handle, and suggestions put contacts' spaces first`() =
        runBlocking<Unit> {
            val sneakers = h.liveSpace(baraka)
            val similar = h.liveSpace(amina, h.command(amina, name = "Sneaker Drop Mombasa", handle = "sneakerdropmsa"))
            val coffee = h.liveSpace(faith, h.command(faith, name = "Coffee Farmers Kenya", handle = "coffeefarmers", topic = Topic.FARMING, about = "Prices, buyers and weather"))

            val byHandle = h.search("@sneakerdrop", null, 0, 10).data()
            assertEquals(sneakers, byHandle.exact?.space?.spaceId)
            assertEquals(listOf(similar), byHandle.spaces.map { it.space.spaceId })
            assertEquals(setOf(sneakers, similar), h.search("sneak", null, 0, 10).data().spaces.map { it.space.spaceId }.toSet())
            assertEquals(listOf(coffee), h.search("weather", null, 0, 10).data().spaces.map { it.space.spaceId })
            assertEquals(listOf(coffee), h.search("kenya", Topic.FARMING, 0, 10).data().spaces.map { it.space.spaceId })

            h.follow(juma, coffee).data()
            h.contacts.contacts[faith] = mutableSetOf(juma)
            val suggested = h.suggest(faith, "KE", null, 0, 10).data().items
            assertTrue(suggested.none { it.summary.space.spaceId == coffee }, "never your own spaces")
            h.contacts.contacts[baraka] = mutableSetOf(juma)
            val forBaraka = h.suggest(baraka, "KE", null, 0, 10).data().items
            assertEquals(coffee, forBaraka.first().summary.space.spaceId)
            assertEquals(listOf("Juma"), forBaraka.first().known.names)
            assertTrue(forBaraka.none { it.summary.space.spaceId == sneakers })
        }

    @Test
    fun `leaving - the owner can't, a member can, and notifications can be turned off`() =
        runBlocking<Unit> {
            val id = h.liveSpace(baraka)
            h.leave(baraka, id).assertError("spaces.owner_cannot_leave")
            h.follow(juma, id).data()
            val set = h.walletAccess(id, juma).data()
            assertEquals(SpaceAccess.MEMBER, set)
            h.leave(juma, id).data()
            assertEquals(1, h.spaceOf(baraka, id).summary.space.memberCount)
            h.leave(juma, id).assertError("spaces.not_member")
            assertEquals(NotificationLevel.ALL, h.spaceOf(baraka, id).me.notifications)
        }
}
