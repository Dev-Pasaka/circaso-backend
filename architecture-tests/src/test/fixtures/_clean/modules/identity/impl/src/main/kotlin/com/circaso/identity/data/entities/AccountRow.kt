package com.circaso.identity.data.entities

import java.util.UUID

/** What a repository reads an `identity.accounts` row into before mapping it to the domain. */
data class AccountRow(val userId: UUID, val active: Boolean)
