package com.circaso.identity.domain.usecases

import io.opentelemetry.api.trace.Span

class Traced {
    suspend operator fun invoke() = Span.current()
}
