package com.circaso.identity.domain.models

import org.jetbrains.exposed.v1.core.Table
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class Bad(val status: HttpStatusCode)
