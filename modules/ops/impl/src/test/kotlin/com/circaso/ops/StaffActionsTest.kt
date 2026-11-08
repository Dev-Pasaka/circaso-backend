package com.circaso.ops

import com.circaso.identity.api.AccountStatus
import com.circaso.messaging.api.ChatId
import com.circaso.messaging.api.OpsGroup
import com.circaso.ops.domain.errors.OpsError
import com.circaso.ops.domain.models.Actor
import com.circaso.ops.domain.models.AuditOutcome
import com.circaso.ops.domain.models.Staff
import com.circaso.ops.domain.support.StaffRoster
import com.circaso.ops.domain.usecases.AddStaff
import com.circaso.ops.domain.usecases.AssignTicket
import com.circaso.ops.domain.usecases.GetDashboard
import com.circaso.ops.domain.usecases.GetGroup
import com.circaso.ops.domain.usecases.GetUserAccount
import com.circaso.ops.domain.usecases.NoteOnTicket
import com.circaso.ops.domain.usecases.ReplyToTicket
import com.circaso.ops.domain.usecases.SearchUsers
import com.circaso.ops.domain.usecases.SignInStaff
import com.circaso.ops.domain.usecases.SuspendUser
import com.circaso.ops.domain.usecases.UnsuspendUser
import com.circaso.ops.domain.usecases.UpdateStaff
import com.circaso.shared.Resource
import com.circaso.shared.StaffRole
import com.circaso.shared.Uuid7
import com.circaso.shared.getOrNull
import com.circaso.wallet.api.GroupWalletRef
import kotlinx.coroutines.test.runTest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StaffActionsTest {
    private val harness = OpsHarness()
    private val koin = harness.koin
    private val support = Actor(UUID.randomUUID(), "support@circaso.com", StaffRole.SUPPORT, "req-1")
    private val admin = Actor(UUID.randomUUID(), "admin@circaso.com", StaffRole.ADMIN, "req-2")

    @Test
    fun `a suspension needs a reason, is audited before and after, and shows on the account`() =
        runTest {
            val user = harness.accounts.add()

            val refused = koin.get<SuspendUser>()(support, user, "  ")
            val done = koin.get<SuspendUser>()(support, user, "Card fraud reported by the bank")
            val account = koin.get<GetUserAccount>()(user).getOrNull()

            assertEquals(Resource.error(OpsError.ReasonRequired), refused)
            assertTrue(harness.accounts.suspendCalls.size == 1, "a refused reason never reaches identity")
            assertIs<Resource.Success<*>>(done)
            assertEquals(AccountStatus.SUSPENDED, account?.account?.status)
            assertEquals("Card fraud reported by the bank", account?.suspension?.reason)
            assertEquals("support@circaso.com", account?.suspension?.byEmail)
            val line = harness.audit.entries.single()
            assertEquals("user.suspend", line.action)
            assertEquals(AuditOutcome.SUCCEEDED, line.outcome)
            assertEquals(user.toString(), line.targetId)
            assertEquals("req-1", line.requestId)
        }

    @Test
    fun `a refused suspension is audited as failed with identity's code`() =
        runTest {
            val user = harness.accounts.add(status = AccountStatus.SUSPENDED)

            val result = koin.get<SuspendUser>()(support, user, "Again, by mistake")

            assertIs<Resource.Error>(result)
            val line = harness.audit.entries.single()
            assertEquals(AuditOutcome.FAILED, line.outcome)
            assertEquals("identity.account_not_active", line.errorCode)
        }

    @Test
    fun `unsuspend gives the account back and clears the suspension from its page`() =
        runTest {
            val user = harness.accounts.add()
            koin.get<SuspendUser>()(support, user, "Investigating a report")

            val result = koin.get<UnsuspendUser>()(admin, user, "Report was false")

            assertEquals(AccountStatus.ACTIVE, result.getOrNull()?.account?.status)
            assertNull(result.getOrNull()?.suspension)
            assertEquals(listOf("user.suspend", "user.unsuspend"), harness.audit.entries.map { it.action })
        }

    @Test
    fun `a bootstrap admin is always an active admin and can't be changed in the portal`() =
        runTest {
            val signedIn = koin.get<SignInStaff>()("Founder@Circaso.com").getOrNull()
            val change =
                koin.get<UpdateStaff>()(
                    admin,
                    StaffRoster.idOf(OpsHarness.BOOTSTRAP),
                    StaffRole.SUPPORT,
                    active = false,
                )

            assertEquals(StaffRole.ADMIN, signedIn?.role)
            assertTrue(signedIn?.fromConfig == true)
            assertEquals(Resource.error(OpsError.StaffFromConfig), change)
        }

    @Test
    fun `staff are added once, switched off by another admin, and then can't sign in`() =
        runTest {
            val added = koin.get<AddStaff>()(admin, "New.Person@Circaso.com", StaffRole.SUPPORT).getOrNull()
            val again = koin.get<AddStaff>()(admin, "new.person@circaso.com", StaffRole.FINANCE)
            val bad = koin.get<AddStaff>()(admin, "not an email", StaffRole.FINANCE)
            assertNotNull(added)
            val self = koin.get<UpdateStaff>()(Actor(added.id, added.email, added.role, null), added.id, null, false)
            val off = koin.get<UpdateStaff>()(admin, added.id, null, active = false)

            assertEquals("new.person@circaso.com", added.email)
            assertEquals(Resource.error(OpsError.StaffExists), again)
            assertEquals(Resource.error(OpsError.EmailInvalid), bad)
            assertEquals(Resource.error(OpsError.CannotChangeSelf), self)
            assertIs<Resource.Success<Staff>>(off)
            assertNull(koin.get<SignInStaff>()("new.person@circaso.com").getOrNull())
            // a malformed email and a change to oneself are refused before anything runs, so they leave no line
            assertEquals(listOf("staff.add", "staff.add", "staff.update"), harness.audit.entries.map { it.action })
            assertEquals(AuditOutcome.FAILED, harness.audit.entries[1].outcome)
        }

    @Test
    fun `a ticket goes only to active staff who work tickets`() =
        runTest {
            val ticket = harness.desk.open(harness.accounts.add())
            val finance = koin.get<AddStaff>()(admin, "finance@circaso.com", StaffRole.FINANCE).getOrNull()!!
            val agent = koin.get<AddStaff>()(admin, "agent@circaso.com", StaffRole.SUPPORT).getOrNull()!!

            val toFinance = koin.get<AssignTicket>()(support, ticket, finance.id)
            val toAgent = koin.get<AssignTicket>()(support, ticket, agent.id)

            assertEquals(Resource.error(OpsError.AssigneeInvalid), toFinance)
            assertEquals("agent@circaso.com", toAgent.getOrNull()?.row?.assigneeEmail)
        }

    @Test
    fun `a reply reaches support with the staff id and is audited without its text, a note too`() =
        runTest {
            val ticket = harness.desk.open(harness.accounts.add())

            koin.get<ReplyToTicket>()(support, ticket, "We refunded the fee.")
            koin.get<NoteOnTicket>()(support, ticket, "Refund ref 991")

            assertEquals(listOf(ticket to support.staffId), harness.desk.replies)
            assertEquals(listOf("ticket.reply", "ticket.note"), harness.audit.entries.map { it.action })
            assertTrue(harness.audit.entries.none { it.detail?.contains("refund", ignoreCase = true) == true })
        }

    @Test
    fun `the dashboard reads only the tiles the role may see`() =
        runTest {
            val forSupport = koin.get<GetDashboard>()(StaffRole.SUPPORT, 30).getOrNull()!!
            val forFinance = koin.get<GetDashboard>()(StaffRole.FINANCE, 30).getOrNull()!!
            val forAdmin = koin.get<GetDashboard>()(StaffRole.ADMIN, 7).getOrNull()!!

            assertNull(forSupport.money)
            assertNotNull(forSupport.tickets)
            assertNotNull(forSupport.users)
            assertNull(forFinance.tickets)
            assertNotNull(forFinance.money)
            assertTrue(
                listOf(forAdmin.users, forAdmin.active, forAdmin.money, forAdmin.groups, forAdmin.tickets).all {
                    it !=
                        null
                },
            )
            assertEquals(forAdmin.to.minusDays(6), forAdmin.from)
            assertIs<Resource.Error>(koin.get<GetDashboard>()(StaffRole.ADMIN, 0))
        }

    @Test
    fun `a group's wallet balances are read only for a role that may see money`() =
        runTest {
            val owner = harness.accounts.add()
            val chat = ChatId(Uuid7.generate())
            val wallet = UUID.randomUUID()
            harness.groups.groups[chat] = OpsGroup(chat, 2, owner, EPOCH)
            harness.wallets.wallets[chat] = GroupWalletRef(chat, wallet, owner, EPOCH)

            val plain = koin.get<GetGroup>()(chat, withMoney = false).getOrNull()!!
            val withMoney = koin.get<GetGroup>()(chat, withMoney = true).getOrNull()!!

            assertNull(plain.holdings)
            assertNotNull(withMoney.holdings)
            assertEquals(listOf(wallet), harness.wallets.holdingsAsked)
            assertTrue(plain.row.hasWallet)
        }

    @Test
    fun `search finds people by name, handle and id`() =
        runTest {
            val ada = harness.accounts.add()
            harness.profiles.name(ada, "Ada Wanjiru", "ada")
            val search = koin.get<SearchUsers>()

            assertEquals(listOf(ada), search("ada w", null, 50).getOrNull()?.items?.map { it.userId })
            assertEquals(listOf(ada), search("@ad", null, 50).getOrNull()?.items?.map { it.userId })
            assertEquals(listOf(ada), search(ada.toString(), null, 50).getOrNull()?.items?.map { it.userId })
            assertEquals(
                "Ada Wanjiru",
                search("", null, 50)
                    .getOrNull()
                    ?.items
                    ?.single()
                    ?.displayName,
            )
        }
}
