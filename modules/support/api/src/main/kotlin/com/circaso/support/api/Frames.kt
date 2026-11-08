package com.circaso.support.api

import kotlinx.serialization.Serializable

/**
 * The frames support sends to the user's phone (module `support`). The socket is the doorbell: the app then reloads the
 * ticket over HTTP (SU2, SU3). Push-worthy when the phone is offline ("Circaso Support replied"), never with the text.
 */
object SupportFrames {
    const val MODULE = "support"

    /** Staff replied on one of the user's tickets. */
    const val TICKET_REPLIED = "ticket.replied"

    /** Staff changed the ticket's status (resolved, closed, reopened). */
    const val TICKET_UPDATED = "ticket.updated"
}

/** A frame support sends. [type] names it in the envelope and is not part of its `data`. */
sealed interface SupportFrame {
    val type: String
}

/** `support/ticket.replied`: no text, only what the app needs to fetch the ticket and badge it. */
@Serializable
data class TicketRepliedFrame(val ticketId: TicketId, val number: String, val status: TicketStatus) : SupportFrame {
    override val type: String get() = SupportFrames.TICKET_REPLIED
}

/** `support/ticket.updated`: the ticket's new status. */
@Serializable
data class TicketUpdatedFrame(val ticketId: TicketId, val number: String, val status: TicketStatus) : SupportFrame {
    override val type: String get() = SupportFrames.TICKET_UPDATED
}
