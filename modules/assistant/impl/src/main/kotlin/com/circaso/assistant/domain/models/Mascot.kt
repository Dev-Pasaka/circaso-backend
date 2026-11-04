package com.circaso.assistant.domain.models

import java.util.UUID

/*
 * Asha's mascot (decision 2026-11-04): the app animates Asha (or Robbie) from three layers, scene, mood and activity,
 * plus a one-shot reaction, and is told when a turn is about hardship. Every value comes from these fixed lists, as
 * lowercase names on the wire; anything else the model writes is dropped, never forwarded. The hints never carry a
 * number, an amount or a name: the lists have none.
 */

/** Where Asha is: the backdrop, from what the conversation is about. */
enum class MascotScene {
    NONE,
    BEACH,
    BEACH_SUNSET,
    GYM,
    OFFICE,
    TRADING,
    DRIVING,
    SAVING,
    SUPPORT,
    CELEBRATION,
    RUNNING,
    SHOPPING,
    HOME,
    SCHOOL,
    CRYPTO,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        /** The scene of a conversation that has none yet. */
        val DEFAULT = OFFICE

        fun of(value: String?): MascotScene? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** Her expression, held while it lasts. */
enum class MascotMood {
    CALM,
    HAPPY,
    EXCITED,
    CONCERNED,
    PROUD,
    THINKING,
    SASSY,
    CURIOUS,
    FOCUSED,
    CARING,
    SLEEPY,
    APOLOGETIC,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun of(value: String?): MascotMood? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** What she is doing right now. */
enum class MascotActivity {
    IDLE,
    LISTENING,
    THINKING,
    SEARCHING_WEB,
    READING_TRANSACTIONS,
    MAKING_CHART,
    WRITING_STATEMENT,
    SPEAKING,
    DONE,
    READING_BOOK,
    SIPPING,
    WATCHING_SUNSET,
    LIFTING,
    DRIVING,
    SINGING,
    JOGGING,
    CARRYING_BAGS,
    YOGA,
    FLEXING,
    JUGGLING,
    EXAMINING_COIN,
    CHECKING_RECEIPT,
    BUILDING_SANDCASTLE,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun of(value: String?): MascotActivity? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** A one-shot animation: plays once, then she goes back to her mood. */
enum class MascotReaction {
    MONEY_RECEIVED,
    PAYMENT_SENT,
    PAYMENT_FAILED,
    THANKS,
    GOAL_REACHED,
    POKE,
    IDLE_QUIRK,
    ;

    val wire: String get() = name.lowercase()

    companion object {
        fun of(value: String?): MascotReaction? = entries.firstOrNull { it.wire == value?.trim()?.lowercase() }
    }
}

/** What the app is told about the mascot at one moment of a turn. [reaction] rides on one frame only. */
data class MascotHints(
    val scene: MascotScene,
    val mood: MascotMood,
    val activity: MascotActivity,
    val reaction: MascotReaction? = null,
    /** The turn is about hardship (low balance, a failed payment, debt, fees, struggling, loss, medical bills). */
    val sensitive: Boolean = false,
)

/**
 * A conversation's scene as the server keeps it between turns, for the hysteresis: [scene] is what the app shows
 * (null before any was chosen), [pending] a different scene proposed on the last turn and not yet taken, [turnId] the
 * last turn whose proposal was counted (a turn that paused for the phone is counted once), [hadSass] whether the
 * conversation has had its one sassy beat.
 */
data class MascotSceneState(
    val scene: MascotScene? = null,
    val pending: MascotScene? = null,
    val turnId: UUID? = null,
    val hadSass: Boolean = false,
) {
    companion object {
        val NEW = MascotSceneState()
    }
}
