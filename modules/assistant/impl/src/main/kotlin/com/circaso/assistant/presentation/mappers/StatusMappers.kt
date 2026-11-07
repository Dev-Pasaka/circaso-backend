package com.circaso.assistant.presentation.mappers

import com.circaso.assistant.domain.models.AshaStatus
import com.circaso.assistant.presentation.dto.AssistantStatusResponse

fun AshaStatus.toResponse() = AssistantStatusResponse(isAvailable, since?.toString())
