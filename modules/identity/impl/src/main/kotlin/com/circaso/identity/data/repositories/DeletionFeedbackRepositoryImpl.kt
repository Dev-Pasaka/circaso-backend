package com.circaso.identity.data.repositories

import com.circaso.identity.data.tables.DeletionFeedbackTable
import com.circaso.identity.domain.models.DeletionFeedback
import com.circaso.infrastructure.api.Database
import com.circaso.shared.Uuid7
import org.jetbrains.exposed.v1.r2dbc.insert

/** Writes deletion feedback for the store; staff read it straight from the table for now. */
class DeletionFeedbackRepositoryImpl(private val database: Database) {
    /** For the store: adds one answer inside the open transaction, under a new time-ordered id. */
    internal suspend fun add(feedback: DeletionFeedback) {
        database.tx("deletion-feedback.add") {
            DeletionFeedbackTable.insert {
                it[feedbackId] = feedback.feedbackId ?: Uuid7.generate(feedback.createdAt)
                it[reason] = feedback.reason.code
                it[details] = feedback.details
                it[origin] = feedback.source
                it[createdAt] = feedback.createdAt
            }
        }
    }
}
