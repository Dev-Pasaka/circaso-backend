package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.models.MascotActivity
import com.circaso.assistant.domain.models.MascotHints
import com.circaso.assistant.domain.models.MascotMood
import com.circaso.assistant.domain.models.MascotReaction
import com.circaso.assistant.domain.models.MascotScene
import com.circaso.assistant.domain.models.MascotSceneState
import com.circaso.assistant.domain.ports.BlockKind
import java.util.UUID

/**
 * The mascot over one turn (decision 2026-11-04). The model proposes scene, mood, activity, a reaction and whether the
 * turn is sensitive in its `<mascot/>` tag ([MascotTagFilter]); the server decides what the app is told:
 *
 * - **The scene has hysteresis.** It changes only when the same new scene is proposed on two turns in a row, or once
 *   with `strong`; a conversation's first proposal is taken at once. The state lives between turns ([state]).
 * - **No sass on hardship or in support.** A sensitive turn (the model says so, or the question reads like hardship)
 *   or a support scene turns `sassy` into `caring`, and a conversation gets one sassy beat at most.
 * - **Activity follows the work.** While a tool runs it comes from the tool ([activityOf]); while a chart or a
 *   statement is being written, from the block; otherwise the model's, or `speaking`. The turn ends on `done`.
 * - **A reaction is sent once.**
 */
class TurnMascot(before: MascotSceneState, private val turnId: UUID, question: String) {
    var state: MascotSceneState = before
        private set

    private var mood: MascotMood = MascotMood.CALM
    private var replyActivity: MascotActivity = MascotActivity.SPEAKING
    private var sensitive: Boolean = isHardship(question)
    private var hasMood = false
    private var isSassTurn = false

    /** Whether a `mascot` frame went out this turn: the first reply text brings one if no tag did. */
    var isAnnounced: Boolean = false
        private set

    /** The model's tag: taken into the turn; the hints to send now. */
    fun proposed(tag: MascotTag): MascotHints {
        sensitive = sensitive || tag.sensitive
        state = decideScene(state, tag.scene, tag.strong, turnId)
        tag.activity?.let { replyActivity = it }
        tag.mood?.let {
            mood = it
            hasMood = true
        }
        isAnnounced = true
        val hints = hints(replyActivity, tag.reaction, proposedScene = tag.scene)
        if (hints.mood == MascotMood.SASSY) state = state.copy(hadSass = true)
        return hints
    }

    /** The reply has started without a tag: the conversation's scene and a calm (or caring) Asha, speaking. */
    fun replying(): MascotHints {
        isAnnounced = true
        return hints(replyActivity)
    }

    /** A tool is about to run. */
    fun toolStarted(toolName: String): MascotHints = hints(activityOf(toolName))

    /** A tool has answered; the model is thinking about what it said. */
    fun toolFinished(): MascotHints = hints(MascotActivity.THINKING)

    /** A rich block opened ([kind]) or closed (null). */
    fun block(kind: BlockKind?): MascotHints =
        hints(
            when (kind) {
                BlockKind.CHART, BlockKind.DIAGRAM -> MascotActivity.MAKING_CHART
                BlockKind.STATEMENT -> MascotActivity.WRITING_STATEMENT
                null -> replyActivity
            },
        )

    /** The turn is over. A turn that proposed no scene breaks a run of proposals, as one that proposed none does. */
    fun done(): MascotHints {
        if (state.turnId != turnId) state = decideScene(state, null, strong = false, turnId)
        return hints(MascotActivity.DONE)
    }

    private fun hints(
        activity: MascotActivity,
        reaction: MascotReaction? = null,
        proposedScene: MascotScene? = null,
    ): MascotHints {
        val scene = state.scene ?: MascotScene.DEFAULT
        val noSass = sensitive || scene == MascotScene.SUPPORT || proposedScene == MascotScene.SUPPORT
        val base = if (!hasMood && sensitive) MascotMood.CARING else mood
        val shown =
            when {
                base != MascotMood.SASSY -> base

                noSass -> MascotMood.CARING

                // one sassy beat per conversation: once it has had it, only on the turn that had it
                state.hadSass && !isSassTurn -> MascotMood.CALM

                else -> base
            }
        if (shown == MascotMood.SASSY) isSassTurn = true
        return MascotHints(scene, shown, activity, reaction, sensitive)
    }

    companion object {
        /**
         * The scene after one turn's proposal. A turn is counted once: the half of a turn that carries on after the
         * phone answered (A9) doesn't count as a second turn in a row.
         */
        fun decideScene(
            state: MascotSceneState,
            proposed: MascotScene?,
            strong: Boolean,
            turnId: UUID,
        ): MascotSceneState {
            if (state.turnId == turnId && proposed == null) return state
            if (state.turnId == turnId && state.scene != null && !strong) return state
            val counted = state.copy(turnId = turnId)
            return when {
                proposed == null -> counted.copy(pending = null)
                state.scene == null -> counted.copy(scene = proposed, pending = null)
                proposed == state.scene -> counted.copy(pending = null)
                strong || proposed == state.pending -> counted.copy(scene = proposed, pending = null)
                else -> counted.copy(pending = proposed)
            }
        }

        /** What she is doing while [toolName] runs: the work it obviously is, or thinking. */
        fun activityOf(toolName: String): MascotActivity =
            when (toolName) {
                "searchWeb", "getCryptoMarket", "getTrendingCrypto" -> {
                    MascotActivity.SEARCHING_WEB
                }

                "listTransactions", "summarizeTransactions", "getTransaction", "getWallet" -> {
                    MascotActivity.READING_TRANSACTIONS
                }

                else -> {
                    MascotActivity.THINKING
                }
            }

        /**
         * A cheap net under the model's `sensitive`: a question that reads like hardship is sensitive whatever the
         * model said. A false alarm only takes the sass away.
         */
        fun isHardship(question: String): Boolean {
            val text = question.lowercase()
            return HARDSHIP.any { it.containsMatchIn(text) }
        }

        /** Word starts (a stem matches its endings: "struggl" is struggle, struggling) and whole words (`\b`). */
        private val HARDSHIP =
            listOf(
                """broke\b""",
                "debt",
                "loan",
                "overdraft",
                """owe\b""",
                """owing\b""",
                "struggl",
                "afford",
                "low balance",
                "no money",
                "fail",
                "declined",
                """fees?\b""",
                "charges",
                "funeral",
                "passed away",
                """died\b""",
                "death",
                "bereave",
                "hospital",
                "medical",
                """sick\b""",
                "arrears",
                "evict",
                "lost my job",
                "jobless",
                "unemployed",
                // Swahili and Sheng
                """deni\b""",
                "madeni",
                "mkopo",
                "mikopo",
                "sina pesa",
                "sina doo",
                "msiba",
                "mazishi",
                "hospitali",
                "mgonjwa",
                "imefeli",
                "imeshindwa",
                """ada\b""",
            ).map { Regex("""\b$it""") }
    }
}
