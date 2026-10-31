package com.circaso.users

import com.circaso.shared.Resource
import com.circaso.users.presentation.mappers.toAgentBlocks
import com.circaso.users.presentation.mappers.toAgentResponse
import com.circaso.users.presentation.services.UsersAgentService
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Asha's agent routes on users (decision 2026-10-31): the caller's own profile and blocks only, no phone number. */
class UsersAgentTest {
    @Test
    fun `the profile is the caller's own and carries no phone number`() =
        runTest {
            val h = Harness()
            val service = UsersAgentService(h.getMyProfile, h.listBlocks)
            val amina = h.person("Amina", "+254712345678")
            h.person("Bob", "+254700000001")

            val profile = service.getProfile(amina).data().toAgentResponse()

            assertEquals(amina.toString(), profile.userId)
            assertEquals("Amina", profile.displayName)
            assertFalse(profile.toString().contains("712345678"), "no phone number reaches the assistant")
        }

    @Test
    fun `blocks are the caller's own, at most 50`() =
        runTest {
            val h = Harness()
            val service = UsersAgentService(h.getMyProfile, h.listBlocks)
            val amina = h.person("Amina")
            val bob = h.person("Bob")
            val cara = h.person("Cara")
            h.blockUser(amina, bob).data()
            h.blockUser(cara, amina).data()

            val blocked = service.listBlocks(amina).data().toAgentBlocks()

            assertEquals(listOf("Bob"), blocked.blocked.map { it.displayName })
            val many = (1..60).map { h.person("P$it") }
            many.forEach { h.blockUser(amina, it).data() }
            assertEquals(
                50,
                service
                    .listBlocks(amina)
                    .data()
                    .toAgentBlocks()
                    .blocked.size,
            )
        }

    private fun <T> Resource<T>.data(): T = (this as Resource.Success).data
}
