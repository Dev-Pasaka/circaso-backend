package com.circaso.identity.api

import com.circaso.identity.presentation.dto.SessionRequest

// An impl file whose package merely has an `api` segment gets no exception.
data class LeakedSessionResponse(val request: SessionRequest)
