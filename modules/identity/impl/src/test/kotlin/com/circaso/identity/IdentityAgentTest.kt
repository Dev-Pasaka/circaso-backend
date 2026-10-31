package com.circaso.identity

import com.circaso.identity.domain.usecases.GetSecuritySummary
import com.circaso.identity.presentation.mappers.toAgentResponse
import com.circaso.identity.presentation.services.IdentityAgentService
import com.circaso.shared.Resource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Asha's security overview (decision 2026-10-31): the caller's own account, masked, with no credentials. */
class IdentityAgentTest {
    @Test
    fun `the overview is the caller's own, masked, and carries nothing that signs in`() =
        runTest {
            val h = Harness()
            val phone = Phone("Pixel 9")
            val amina = h.signUp("amina", phone, "+254712345678", email = "amina.wanjiku@gmail.com")
            h.signUp("bob", Phone("Galaxy"), "+254700000001", email = "bob@example.com")
            val service = IdentityAgentService(GetSecuritySummary(h.store, h.store, h.store, h.store))

            val agent = h.principalOf(amina.agentSession.accessToken)
            val overview = (service.getSecurity(agent) as Resource.Success).data.toAgentResponse()

            assertEquals("ACTIVE", overview.status)
            assertEquals(listOf("google"), overview.signIns.map { it.provider })
            assertEquals("am•••@gmail.com", overview.signIns.single().maskedEmail)
            assertEquals("Pixel 9", overview.device?.model)
            assertTrue(overview.recentEvents.size <= 10)
            val text = overview.toString()
            listOf("712345678", "amina.wanjiku", "bob", "Galaxy", phone.publicKey, phone.deviceId.toString()).forEach {
                assertFalse(text.contains(it), "the overview leaks '$it'")
            }
            assertTrue(overview.maskedPhone!!.isNotBlank())
        }
}
