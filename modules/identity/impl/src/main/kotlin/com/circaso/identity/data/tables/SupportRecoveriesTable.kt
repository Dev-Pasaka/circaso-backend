package com.circaso.identity.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/**
 * `identity.support_recoveries`: support cases for a lost Google/Apple login, keyed by request id. The SQL orders the
 * user index `request_id DESC`, which Exposed's index declaration can't say.
 */
object SupportRecoveriesTable : Table("identity.support_recoveries") {
    val requestId = javaUUID("request_id")
    val userId = javaUUID("user_id")
    val status = text("status")
    val contactEmail = text("contact_email")
    val note = text("note").nullable()
    val idCheck = text("id_check").nullable()
    val selfieMatch = text("selfie_match").nullable()
    val agentId = text("agent_id").nullable()
    val noticeSentAt = timestamp("notice_sent_at").nullable()
    val relinkAfter = timestamp("relink_after").nullable()
    val newProvider = text("new_provider").nullable()
    val newSubject = text("new_subject").nullable()
    override val primaryKey = PrimaryKey(requestId)

    init {
        index("support_recoveries_user_idx", false, userId, requestId)
        index("support_recoveries_relink_idx", false, relinkAfter, filterCondition = { status eq "NOTICE_SENT" })
        index("support_recoveries_status_idx", false, status, requestId)
    }
}
