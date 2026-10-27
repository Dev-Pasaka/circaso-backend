package com.circaso.identity.data.tables

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.java.javaUUID
import org.jetbrains.exposed.v1.javatime.timestamp

/** `identity.provider_links`: an account's Google and Apple logins. `(provider, subject)` is unique: the claim. */
object ProviderLinksTable : Table("identity.provider_links") {
    /** The constraint whose violation means another account holds the login. */
    const val LOGIN_KEY = "provider_links_login_key"

    val userId = javaUUID("user_id")
    val provider = text("provider")
    val subject = text("subject")
    val email = text("email").nullable()
    val linkedAt = timestamp("linked_at")
    override val primaryKey = PrimaryKey(userId, provider)

    init {
        uniqueIndex(LOGIN_KEY, provider, subject)
    }
}
