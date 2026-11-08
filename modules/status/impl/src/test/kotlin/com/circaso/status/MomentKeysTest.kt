package com.circaso.status

import com.circaso.shared.UserId
import com.circaso.status.api.MomentId
import com.circaso.status.api.MomentKeyRequestedFrame
import com.circaso.status.api.MomentKeysReadyFrame
import com.circaso.status.domain.errors.StatusError
import com.circaso.status.domain.models.MomentKeyCopy
import com.circaso.status.presentation.dto.KeyCopiesRequest
import com.circaso.status.presentation.dto.MomentKeyCopyDto
import com.circaso.status.presentation.mappers.toAnswered
import com.circaso.status.presentation.mappers.toCopies
import kotlinx.coroutines.test.runTest
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The key request (a new phone can't open moments sealed for its old Signal identity): who gets asked, how often,
 * and the poster's fresh copies replacing the old ones.
 */
class MomentKeysTest {
    private val h = StatusHarness()
    private val poster = UserId.new()
    private val amina = UserId.new()
    private val baraka = UserId.new()

    private fun requested() = h.frames.sent.filter { it.second is MomentKeyRequestedFrame }

    @Test
    fun `a poster showing me a live moment is asked once, and again only after 10 minutes`() =
        runTest {
            h.posted(poster, amina)

            assertEquals(listOf(poster), h.requestKeys(amina, listOf(poster)).data())
            assertEquals(listOf(poster to MomentKeyRequestedFrame(amina)), requested())

            assertEquals(emptyList(), h.requestKeys(amina, listOf(poster)).data())
            h.clock.advance(Duration.ofMinutes(9))
            assertEquals(emptyList(), h.requestKeys(amina, listOf(poster)).data())
            h.clock.advance(Duration.ofMinutes(1))
            assertEquals(listOf(poster), h.requestKeys(amina, listOf(poster)).data())
            assertEquals(2, requested().size)
        }

    @Test
    fun `nobody is asked for moments I wasn't shown, that expired, or across a block`() =
        runTest {
            h.posted(poster, amina)
            val stranger = UserId.new()
            val blocker = UserId.new()
            h.posted(blocker, amina)
            h.reach.blocked += setOf(blocker, amina)

            assertEquals(emptyList(), h.requestKeys(baraka, listOf(poster)).data())
            assertEquals(emptyList(), h.requestKeys(amina, listOf(stranger, blocker)).data())
            h.clock.advance(Duration.ofHours(24))
            assertEquals(emptyList(), h.requestKeys(amina, listOf(poster)).data())
            assertTrue(requested().isEmpty())
        }

    @Test
    fun `a request names 1 to 100 distinct posters, never myself`() =
        runTest {
            h.requestKeys(amina, emptyList()).assertError(StatusError.KeysInvalid)
            h.requestKeys(amina, listOf(amina)).assertError(StatusError.KeysInvalid)
            h.requestKeys(amina, listOf(poster, poster)).assertError(StatusError.KeysInvalid)
            h.requestKeys(amina, List(101) { UserId.new() }).assertError(StatusError.KeysInvalid)
        }

    @Test
    fun `fresh copies replace the old ones on my live moments, and the person is told once`() =
        runTest {
            val first = h.posted(poster, amina).momentId
            val second = h.posted(poster, amina).momentId
            val copies = listOf(MomentKeyCopy(first, amina, h.base64(40)), MomentKeyCopy(second, amina, h.base64(41)))

            assertEquals(2, h.shareKeyCopies(poster, copies).data())

            assertEquals(h.base64(40), h.moments.member(first, amina)?.keyCopy)
            assertEquals(h.base64(41), h.moments.member(second, amina)?.keyCopy)
            assertEquals(
                listOf<Any>(MomentKeysReadyFrame(poster)),
                h.frames.to(amina).filterIsInstance<MomentKeysReadyFrame>(),
            )
        }

    @Test
    fun `copies for someone else's moment, an expired one, or a person not in the audience are skipped`() =
        runTest {
            val mine = h.posted(poster, amina).momentId
            val theirs = h.posted(baraka, amina).momentId
            val before = h.moments.member(theirs, amina)?.keyCopy

            assertEquals(0, h.shareKeyCopies(poster, listOf(MomentKeyCopy(theirs, amina, h.base64(9)))).data())
            assertEquals(before, h.moments.member(theirs, amina)?.keyCopy)
            assertEquals(0, h.shareKeyCopies(poster, listOf(MomentKeyCopy(mine, baraka, h.base64(9)))).data())
            h.reach.blocked += setOf(poster, amina)
            assertEquals(0, h.shareKeyCopies(poster, listOf(MomentKeyCopy(mine, amina, h.base64(9)))).data())
            h.reach.blocked.clear()
            h.clock.advance(Duration.ofHours(24))
            assertEquals(0, h.shareKeyCopies(poster, listOf(MomentKeyCopy(mine, amina, h.base64(9)))).data())
            assertTrue(h.frames.sent.none { it.second is MomentKeysReadyFrame })
        }

    @Test
    fun `an answer with no copy for someone still tells them, so their phone stops waiting`() =
        runTest {
            // My moment from an earlier phone: this phone has no body to seal again for Amina's new phone.
            h.posted(poster, amina)

            assertEquals(0, h.shareKeyCopies(poster, emptyList(), answered = listOf(amina)).data())

            assertEquals(
                listOf<Any>(MomentKeysReadyFrame(poster)),
                h.frames.to(amina).filterIsInstance<MomentKeysReadyFrame>(),
            )
        }

    @Test
    fun `someone answered and copied is told once`() =
        runTest {
            val id = h.posted(poster, amina).momentId

            val copies = listOf(MomentKeyCopy(id, amina, h.base64(40)))
            assertEquals(1, h.shareKeyCopies(poster, copies, answered = listOf(amina)).data())

            assertEquals(
                1,
                h.frames
                    .to(amina)
                    .filterIsInstance<MomentKeysReadyFrame>()
                    .size,
            )
        }

    @Test
    fun `an answer tells nobody who isn't shown my live moments, or across a block`() =
        runTest {
            h.posted(poster, amina)

            assertEquals(0, h.shareKeyCopies(poster, emptyList(), answered = listOf(baraka)).data())
            h.reach.blocked += setOf(poster, amina)
            assertEquals(0, h.shareKeyCopies(poster, emptyList(), answered = listOf(amina)).data())
            h.reach.blocked.clear()
            h.clock.advance(Duration.ofHours(24))
            assertEquals(0, h.shareKeyCopies(poster, emptyList(), answered = listOf(amina)).data())
            assertTrue(h.frames.sent.none { it.second is MomentKeysReadyFrame })
        }

    @Test
    fun `answered names distinct people, never myself, at most 100, and an answer isn't empty`() =
        runTest {
            h.shareKeyCopies(poster, emptyList(), answered = emptyList()).assertError(StatusError.KeysInvalid)
            h.shareKeyCopies(poster, emptyList(), answered = listOf(poster)).assertError(StatusError.KeysInvalid)
            h.shareKeyCopies(poster, emptyList(), answered = listOf(amina, amina)).assertError(StatusError.KeysInvalid)
            h
                .shareKeyCopies(poster, emptyList(), answered = List(101) { UserId.new() })
                .assertError(StatusError.KeysInvalid)
        }

    @Test
    fun `answered must be user ids`() {
        val bad = KeyCopiesRequest(answered = listOf("nope"))
        assertIs<com.circaso.shared.Resource.Error>(bad.toAnswered())
        val good = KeyCopiesRequest(answered = listOf(UserId.new().toString()))
        assertEquals(1, good.toAnswered().data().size)
    }

    @Test
    fun `copies are at most 200, one per moment and person, never for myself, and not too large`() =
        runTest {
            val id = h.posted(poster, amina).momentId
            h.shareKeyCopies(poster, emptyList()).assertError(StatusError.KeysInvalid)
            h
                .shareKeyCopies(
                    poster,
                    listOf(MomentKeyCopy(id, poster, h.base64(8))),
                ).assertError(StatusError.KeysInvalid)
            h
                .shareKeyCopies(
                    poster,
                    listOf(MomentKeyCopy(id, amina, h.base64(8)), MomentKeyCopy(id, amina, h.base64(9))),
                ).assertError(StatusError.KeysInvalid)
            h
                .shareKeyCopies(poster, List(201) { MomentKeyCopy(MomentId.new(), amina, h.base64(8)) })
                .assertError(StatusError.KeysInvalid)
            h
                .shareKeyCopies(poster, listOf(MomentKeyCopy(id, amina, h.base64(72 * 1_024 + 1))))
                .assertError(StatusError.KeysInvalid)
        }

    @Test
    fun `a copy needs a moment id, a user id and base64`() {
        val bad = KeyCopiesRequest(listOf(MomentKeyCopyDto("nope", UserId.new().toString(), "AA==")))
        assertIs<com.circaso.shared.Resource.Error>(bad.toCopies())
        val good =
            KeyCopiesRequest(listOf(MomentKeyCopyDto(MomentId.new().toString(), UserId.new().toString(), "AA==")))
        assertEquals(1, good.toCopies().data().size)
    }
}
