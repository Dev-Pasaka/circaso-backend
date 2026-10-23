package com.circaso.identity.presentation.dto

import kotlinx.serialization.Serializable

@Serializable
data class AccountResponse(val userId: String, val active: Boolean)
