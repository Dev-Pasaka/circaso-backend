package com.circaso.identity

import com.circaso.identity.api.AccountStatus
import com.circaso.identity.api.DeletionGuard
import com.circaso.identity.domain.models.LeavingReason
import com.circaso.identity.domain.models.SecurityEventType
import com.circaso.identity.domain.models.SessionStatus
import com.circaso.identity.domain.models.WebDeletionCommand
import com.circaso.identity.domain.models.WebDeletionOutcome
import com.circaso.identity.presentation.dto.WebDeletionRequest
import com.circaso.identity.presentation.mappers.toCommand
import com.circaso.identity.presentation.mappers.toResponse
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.DomainError
import com.circaso.shared.ErrorKind
import com.circaso.shared.Resource
import com.circaso.shared.UserId
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** I17: the website's account deletion page, proved by a Google or Apple sign-in instead of a session. */
class WebAccountDeletionTest {
    private object FundsRemaining : DomainError {
        override val code = "wallet.funds_remaining"
        override val message = "Move your money out first."
        override val kind = ErrorKind.CONFLICT
    }

    private val walletWithFunds =
        object : DeletionGuard {
            override suspend fun canDelete(userId: UserId): Resource<Unit> = Resource.error(FundsRemaining)
        }

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data

    private fun Resource<*>.error(): DomainError = (this as Resource.Error).error

    private suspend fun Harness.command(
        sub: String,
        reason: LeavingReason = LeavingReason.PRIVACY,
        details: String? = "Too many notifications",
        token: String = "dev:$sub",
    ) = WebDeletionCommand(SocialProvider.GOOGLE, token, nonce(), reason, details)

    @Test
    fun `a Google sign-in for an existing login schedules the deletion, ends every session and keeps the reason`() =
        runTest {
            val h = Harness()
            val registered = h.signUp("alice", Phone(), "+254712345678")
            val userId = registered.account.userId
            val sessionId = h.principalOf(registered.session.accessToken).sessionId

            val outcome = h.webDelete(h.command("alice")).data()

            val scheduled = assertIs<WebDeletionOutcome.Scheduled>(outcome)
            val account = h.store.accounts.getValue(userId)
            assertEquals(AccountStatus.DELETED, account.status)
            assertEquals(scheduled.deleteAfter, account.deleteAfter)
            assertTrue(
                h.store.sessions.values
                    .filter { it.userId == userId }
                    .all { it.status == SessionStatus.REVOKED },
            )
            assertTrue(sessionId in h.cache.revoked, "the access token stops now")
            assertEquals("identity.session_revoked", h.verifyToken(registered.session.accessToken).error().code)
            val logged = h.store.log.single { it.type == SecurityEventType.DELETION_REQUESTED }
            assertEquals("web", logged.detail)
            assertTrue(h.store.events.any { it.type == "identity.AccountStatusChanged" })
            val feedback = h.store.feedback.single()
            assertEquals(LeavingReason.PRIVACY, feedback.reason)
            assertEquals("Too many notifications", feedback.details)
            assertEquals("web", feedback.source)

            val again = h.webDelete(h.command("alice")).data()
            assertEquals(WebDeletionOutcome.AlreadyScheduled(account.deleteAfter), again)
            assertEquals(1, h.store.feedback.size, "a repeat stores nothing")
        }

    @Test
    fun `a login no account has answers no_account and changes nothing`() =
        runTest {
            val h = Harness()
            h.signUp("alice", Phone(), "+254712345678")

            assertEquals(WebDeletionOutcome.NoAccount, h.webDelete(h.command("stranger")).data())
            assertTrue(
                h.store.accounts.values
                    .none { it.status == AccountStatus.DELETED },
            )
            assertTrue(h.store.feedback.isEmpty())
        }

    @Test
    fun `a token that doesn't verify is refused as unauthorized`() =
        runTest {
            val h = Harness()
            h.signUp("alice", Phone(), "+254712345678")

            val error = h.webDelete(h.command("alice", token = "forged")).error()
            assertEquals("identity.provider_token_invalid", error.code)
            assertEquals(ErrorKind.UNAUTHORIZED, error.kind)
        }

    @Test
    fun `a nonce we never issued, or one already used, is refused`() =
        runTest {
            val h = Harness()
            h.signUp("alice", Phone(), "+254712345678")
            val command = h.command("alice")

            assertIs<WebDeletionOutcome.Scheduled>(h.webDelete(command).data())
            assertEquals("identity.nonce_invalid", h.webDelete(command).error().code)
            assertEquals("identity.nonce_invalid", h.webDelete(command.copy(nonce = "made-up")).error().code)
        }

    @Test
    fun `the wallet guard's refusal comes back with its code and nothing changes`() =
        runTest {
            val h = Harness(guards = listOf(walletWithFunds))
            val registered = h.signUp("alice", Phone(), "+254712345678")
            val eventsBefore = h.store.events.size

            val outcome = h.webDelete(h.command("alice")).data()

            assertEquals("wallet.funds_remaining", assertIs<WebDeletionOutcome.Refused>(outcome).code)
            assertEquals(
                AccountStatus.ACTIVE,
                h.store.accounts
                    .getValue(registered.account.userId)
                    .status,
            )
            assertIs<Resource.Success<*>>(h.verifyToken(registered.session.accessToken), "the session still works")
            assertTrue(h.store.feedback.isEmpty())
            assertEquals(eventsBefore, h.store.events.size)
        }

    @Test
    fun `in-app deletion still ends only its own session and asks the guards`() =
        runTest {
            val refused = Harness(guards = listOf(walletWithFunds))
            val blocked = refused.signUp("bob", Phone(), "+254712345679")
            val principal = refused.principalOf(blocked.session.accessToken)
            assertEquals("wallet.funds_remaining", refused.delete(principal).error().code)

            val h = Harness()
            val registered = h.signUp("alice", Phone(), "+254712345678")
            assertIs<Resource.Success<Unit>>(h.delete(h.principalOf(registered.session.accessToken)))
            val account = h.store.accounts.getValue(registered.account.userId)
            assertEquals(AccountStatus.DELETED, account.status)
            assertTrue(h.store.feedback.isEmpty())
            assertNull(
                h.store.log
                    .single { it.type == SecurityEventType.DELETION_REQUESTED }
                    .detail,
            )
        }

    @Test
    fun `the request is checked for shape before anything runs`() {
        fun request(
            provider: String = "google",
            reason: String = "privacy",
            details: String? = null,
            nonce: String? = "n",
        ) = WebDeletionRequest(provider, "dev:alice", nonce, reason, details)

        assertEquals(LeavingReason.TOO_MANY_APPS, request(reason = "too_many_apps").toCommand().data().reason)
        assertEquals(SocialProvider.APPLE, request(provider = "apple").toCommand().data().provider)
        assertNull(request(details = "   ").toCommand().data().details)
        assertEquals("request.malformed", request(reason = "bored").toCommand().error().code)
        assertEquals("request.malformed", request(provider = "facebook").toCommand().error().code)
        assertEquals("request.malformed", request(details = "x".repeat(501)).toCommand().error().code)
        assertEquals("identity.nonce_invalid", request(nonce = null).toCommand().error().code)
    }

    @Test
    fun `the answer names no account`() {
        assertEquals("no_account", WebDeletionOutcome.NoAccount.toResponse().status)
        val refused = WebDeletionOutcome.Refused("wallet.funds_remaining", "Move your money out first.").toResponse()
        assertEquals("refused", refused.status)
        assertEquals("wallet.funds_remaining", refused.code)
        assertNull(refused.deleteAfter)
    }
}
