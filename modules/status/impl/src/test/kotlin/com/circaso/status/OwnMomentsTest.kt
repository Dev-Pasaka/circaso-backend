package com.circaso.status

import com.circaso.shared.UserId
import com.circaso.status.domain.models.ReactionChange
import com.circaso.status.domain.support.HEART
import com.circaso.status.domain.usecases.ListOwnMoments
import com.circaso.status.presentation.mappers.toAgentResponse
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Asha's own-moments route (decision 2026-11-05): counts and times of the caller's live moments, nothing sealed. */
class OwnMomentsTest {
    private val h = StatusHarness()
    private val own = ListOwnMoments(h.moments, h.clock)
    private val poster = UserId.new()
    private val amina = UserId.new()
    private val baraka = UserId.new()

    @Test
    fun `the caller's live moments come newest first with their views and reactions, never the content`() =
        runTest {
            val first = h.post(h.command(poster, listOf(amina, baraka))).data()
            h.clock.advance(Duration.ofHours(1))
            val second = h.post(h.command(poster, listOf(amina))).data()
            h.post(h.command(amina, listOf(poster))).data() // someone else's moment is not the caller's
            h.view(amina, first.momentId, ReactionChange.Set(HEART)).data()
            h.view(baraka, first.momentId, ReactionChange.Keep).data()

            val answer = own(poster).data().toAgentResponse()

            assertEquals(2, answer.live)
            assertEquals(
                listOf(second.momentId.toString(), first.momentId.toString()),
                answer.moments.map { it.momentId },
            )
            assertEquals(2, answer.moments.last().viewCount)
            assertEquals(1, answer.moments.last().reactionCount)
            assertEquals(0, answer.moments.first().viewCount)
            assertEquals(2, answer.totalViews)
            assertEquals(1, answer.totalReactions)
            assertFalse(answer.moments.first().hasMedia)
            val json = answer.toString()
            assertFalse(amina.toString() in json, "never who viewed")
        }

    @Test
    fun `an expired moment is not live, and no moments is an empty answer`() =
        runTest {
            h.post(h.command(poster, listOf(amina))).data()
            h.clock.advance(Duration.ofHours(25))

            val answer = own(poster).data().toAgentResponse()

            assertEquals(0, answer.live)
            assertTrue(answer.moments.isEmpty())
        }
}
