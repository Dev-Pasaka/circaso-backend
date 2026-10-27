package com.circaso.users.data.entities

import java.util.UUID

/** What a repository reads a `users.profiles` row into before mapping it to the domain. */
data class ProfileRow(val userId: UUID, val displayName: String)
