package com.circaso.identity.domain.usecases

import com.circaso.identity.presentation.dto.SignInRequest

class Leaky {
    suspend operator fun invoke(request: SignInRequest) = Unit
}

data class SignInResponse(val token: String)
