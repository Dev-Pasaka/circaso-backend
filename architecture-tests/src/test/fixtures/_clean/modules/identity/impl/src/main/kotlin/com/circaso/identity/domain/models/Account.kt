package com.circaso.identity.domain.models

import com.circaso.shared.UserId

data class Account(val userId: UserId, val active: Boolean)
