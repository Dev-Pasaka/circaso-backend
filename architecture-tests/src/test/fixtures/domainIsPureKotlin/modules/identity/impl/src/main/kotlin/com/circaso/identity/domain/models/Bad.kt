package com.circaso.identity.domain.models

import com.kandraorm.kandra.annotations.PartitionKey
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.Serializable

@Serializable
data class Bad(val status: HttpStatusCode)
