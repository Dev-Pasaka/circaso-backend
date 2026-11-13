package com.circaso.calls.data.repositories

import com.circaso.calls.api.CallId
import com.circaso.calls.data.tables.ReportsTable
import com.circaso.calls.domain.models.CallReport
import com.circaso.calls.domain.models.ReportEvent
import com.circaso.calls.domain.models.ReportSample
import com.circaso.calls.domain.models.ReportSummary
import com.circaso.calls.domain.repositories.CallReportRepository
import com.circaso.infrastructure.api.Database
import com.circaso.shared.CircasoJson
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.time.Duration

/**
 * `calls.reports`: the timeline, samples and summary are kept as JSON text, in the shapes below, and never read by the
 * database. A report expires [RETENTION] after it came; `calls.purge-expired` deletes it then.
 */
class CallReportRepositoryImpl(private val database: Database) : CallReportRepository {
    override suspend fun upsert(report: CallReport) {
        database.tx("reports.upsert") {
            ReportsTable.upsert(ReportsTable.callId, ReportsTable.userId) {
                it[callId] = report.callId.value
                it[userId] = report.userId.value
                it[platform] = report.platform
                it[appVersion] = report.appVersion
                it[osVersion] = report.osVersion
                it[deviceModel] = report.deviceModel
                it[video] = report.video
                it[outgoing] = report.outgoing
                it[endCause] = report.endCause
                it[startedAt] = report.startedAt
                it[endedAt] = report.endedAt
                it[receivedAt] = report.receivedAt
                it[expiresAt] = report.receivedAt.plus(RETENTION)
                it[summary] = CircasoJson.encodeToString(StoredSummary.serializer(), report.summary.stored())
                it[events] =
                    CircasoJson.encodeToString(EVENTS, report.events.map { e -> StoredEvent(e.t, e.type, e.detail) })
                it[samples] = CircasoJson.encodeToString(SAMPLES, report.samples.map { s -> s.stored() })
            }
        }
    }

    override suspend fun forCall(callId: CallId): List<CallReport> =
        database.tx("reports.for-call") {
            ReportsTable
                .selectAll()
                .where { ReportsTable.callId eq callId.value }
                .map { it.toReport() }
                .toList()
        }

    private fun ResultRow.toReport() =
        CallReport(
            callId = CallId(this[ReportsTable.callId]),
            userId = UserId(this[ReportsTable.userId]),
            platform = this[ReportsTable.platform],
            appVersion = this[ReportsTable.appVersion],
            osVersion = this[ReportsTable.osVersion],
            deviceModel = this[ReportsTable.deviceModel],
            video = this[ReportsTable.video],
            outgoing = this[ReportsTable.outgoing],
            endCause = this[ReportsTable.endCause],
            startedAt = this[ReportsTable.startedAt],
            endedAt = this[ReportsTable.endedAt],
            receivedAt = this[ReportsTable.receivedAt],
            summary = CircasoJson.decodeFromString(StoredSummary.serializer(), this[ReportsTable.summary]).model(),
            events =
                CircasoJson
                    .decodeFromString(
                        EVENTS,
                        this[ReportsTable.events],
                    ).map { ReportEvent(it.t, it.type, it.detail) },
            samples = CircasoJson.decodeFromString(SAMPLES, this[ReportsTable.samples]).map { it.model() },
        )

    private companion object {
        val RETENTION: Duration = Duration.ofDays(30)
        val EVENTS = ListSerializer(StoredEvent.serializer())
        val SAMPLES = ListSerializer(StoredSample.serializer())
    }
}

@Serializable
private data class StoredEvent(val t: Long, val type: String, val detail: String)

@Serializable
private data class StoredSample(
    val t: Long,
    val rttMs: Double? = null,
    val jitterMs: Double? = null,
    val lossPct: Double? = null,
    val packetsReceived: Long? = null,
    val bytesReceived: Long? = null,
) {
    fun model() = ReportSample(t, rttMs, jitterMs, lossPct, packetsReceived, bytesReceived)
}

private fun ReportSample.stored() = StoredSample(t, rttMs, jitterMs, lossPct, packetsReceived, bytesReceived)

@Serializable
private data class StoredSummary(
    val durationMs: Long? = null,
    val samples: Int? = null,
    val avgRttMs: Double? = null,
    val maxRttMs: Double? = null,
    val avgJitterMs: Double? = null,
    val maxJitterMs: Double? = null,
    val lossPct: Double? = null,
    val packetsReceived: Long? = null,
    val bytesReceived: Long? = null,
    val reconnects: Int? = null,
) {
    fun model() =
        ReportSummary(
            durationMs,
            samples,
            avgRttMs,
            maxRttMs,
            avgJitterMs,
            maxJitterMs,
            lossPct,
            packetsReceived,
            bytesReceived,
            reconnects,
        )
}

private fun ReportSummary.stored() =
    StoredSummary(
        durationMs,
        samples,
        avgRttMs,
        maxRttMs,
        avgJitterMs,
        maxJitterMs,
        lossPct,
        packetsReceived,
        bytesReceived,
        reconnects,
    )
