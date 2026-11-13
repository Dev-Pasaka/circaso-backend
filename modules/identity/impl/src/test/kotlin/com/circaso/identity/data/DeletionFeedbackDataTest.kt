package com.circaso.identity.data

import com.circaso.identity.Harness
import com.circaso.identity.Phone
import com.circaso.identity.data.PostgresIdentity.Companion.count
import com.circaso.identity.domain.models.LeavingReason
import com.circaso.identity.domain.models.WebDeletionCommand
import com.circaso.identity.domain.models.WebDeletionOutcome
import com.circaso.integrations.api.SocialProvider
import com.circaso.shared.Resource
import com.circaso.shared.getOrNull
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** I17 on PostgreSQL: the deletion lands with its feedback row (V7), which carries no user id. */
class DeletionFeedbackDataTest {
    private val pg = PostgresIdentity()

    @BeforeTest
    fun clean() = PostgresIdentity.reset()

    @Test
    fun `a website deletion stores the reason apart from the account and ends every session`() =
        runBlocking<Unit> {
            val h = Harness(ports = pg.ports())
            val registered = h.signUp("alice", Phone(), "+254712345678")
            val command =
                WebDeletionCommand(SocialProvider.GOOGLE, "dev:alice", h.nonce(), LeavingReason.SWITCHING, "Moving on")

            val outcome = h.webDelete(command)

            assertIs<WebDeletionOutcome.Scheduled>((outcome as Resource.Success).data)
            val userId = registered.account.userId
            assertEquals(
                1,
                count("SELECT count(*) FROM identity.accounts WHERE user_id = '$userId' AND status = 'DELETED'"),
            )
            assertEquals(
                0,
                count("SELECT count(*) FROM identity.sessions WHERE user_id = '$userId' AND status = 'ACTIVE'"),
            )
            assertEquals(
                1,
                count(
                    "SELECT count(*) FROM identity.deletion_feedback " +
                        "WHERE reason = 'switching' AND details = 'Moving on' AND source = 'web'",
                ),
            )
            assertEquals(null, h.verifyToken(registered.session.accessToken).getOrNull())
        }
}
