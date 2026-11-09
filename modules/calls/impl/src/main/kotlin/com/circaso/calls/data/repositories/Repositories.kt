package com.circaso.calls.data.repositories

import com.circaso.calls.api.CallId
import com.circaso.calls.data.tables.CallsTable
import com.circaso.calls.data.tables.ParticipantsTable
import com.circaso.calls.data.tables.TracksTable
import com.circaso.calls.domain.models.Call
import com.circaso.calls.domain.models.CallKind
import com.circaso.calls.domain.models.CallState
import com.circaso.calls.domain.models.CallTrack
import com.circaso.calls.domain.models.EndReason
import com.circaso.calls.domain.models.Participant
import com.circaso.calls.domain.models.ParticipantState
import com.circaso.calls.domain.ports.CallsOutbox
import com.circaso.calls.domain.repositories.CallRepository
import com.circaso.infrastructure.api.Database
import com.circaso.messaging.api.ChatId
import com.circaso.shared.EventEnvelope
import com.circaso.shared.UserId
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.singleOrNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.vendors.ForUpdateOption
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.upsert
import java.time.Instant

class CallRepositoryImpl(private val database: Database) : CallRepository {
    override suspend fun insert(call: Call): Boolean =
        database.tx("calls.insert") {
            // the partial unique index on (chat_id) WHERE state <> 'ENDED' refuses a second live call in the chat
            val inserted =
                CallsTable
                    .insertIgnore {
                        it[callId] = call.callId.value
                        it[chatId] = call.chatId.value
                        it[kind] = call.kind.name
                        it[state] = call.state.name
                        it[startedBy] = call.startedBy.value
                        it[isGroup] = call.isGroup
                        it[createdAt] = call.createdAt
                        it[startedAt] = call.startedAt
                        it[endedAt] = call.endedAt
                        it[endReason] = call.endReason?.name
                        it[activeAt] = call.activeAt
                    }.insertedCount > 0
            if (inserted) call.participants.forEach { writeParticipant(call.callId, it) }
            inserted
        }

    override suspend fun find(callId: CallId): Call? =
        database.tx("calls.find") {
            CallsTable
                .selectAll()
                .where { CallsTable.callId eq callId.value }
                .map { it }
                .singleOrNull()
                ?.let { withParticipants(it) }
        }

    override suspend fun lock(callId: CallId): Call? =
        database.tx("calls.lock") {
            CallsTable
                .selectAll()
                .where { CallsTable.callId eq callId.value }
                .forUpdate(ForUpdateOption.ForUpdate)
                .map { it }
                .singleOrNull()
                ?.let { withParticipants(it) }
        }

    override suspend fun live(chatId: ChatId): Call? =
        database.tx("calls.live") {
            CallsTable
                .selectAll()
                .where { (CallsTable.chatId eq chatId.value) and (CallsTable.state neq CallState.ENDED.name) }
                .map { it }
                .singleOrNull()
                ?.let { withParticipants(it) }
        }

    override suspend fun liveFor(userId: UserId): List<Call> =
        database.tx("calls.live-for") {
            // the calls that haven't ended are few (the partial index), then one primary-key probe for the person
            val live =
                CallsTable
                    .selectAll()
                    .where { CallsTable.state neq CallState.ENDED.name }
                    .map { it[CallsTable.callId] }
                    .toList()
            if (live.isEmpty()) return@tx emptyList()
            val mine =
                ParticipantsTable
                    .selectAll()
                    .where { (ParticipantsTable.callId inList live) and (ParticipantsTable.userId eq userId.value) }
                    .map { it[ParticipantsTable.callId] }
                    .toList()
            if (mine.isEmpty()) return@tx emptyList()
            CallsTable
                .selectAll()
                .where { (CallsTable.callId inList mine) and (CallsTable.state neq CallState.ENDED.name) }
                .map { it }
                .toList()
                .map { withParticipants(it) }
        }

    override suspend fun save(before: Call, after: Call) {
        database.tx("calls.save") {
            CallsTable.update({ CallsTable.callId eq after.callId.value }) {
                it[state] = after.state.name
                it[startedAt] = after.startedAt
                it[endedAt] = after.endedAt
                it[endReason] = after.endReason?.name
                it[activeAt] = after.activeAt
            }
            val was = before.participants.associateBy { it.userId }
            after.participants
                .filter { was[it.userId] != it }
                .forEach { writeParticipant(after.callId, it) }
        }
    }

    override suspend fun touch(callId: CallId, at: Instant) {
        database.tx("calls.touch") {
            CallsTable.update({ CallsTable.callId eq callId.value }) { it[activeAt] = at }
        }
    }

    override suspend fun ringingBefore(cutoff: Instant): List<CallId> =
        database.tx("calls.ringing-before") {
            val live =
                CallsTable
                    .selectAll()
                    .where { (CallsTable.state neq CallState.ENDED.name) and (CallsTable.createdAt less cutoff) }
                    .map { it[CallsTable.callId] }
                    .toList()
            if (live.isEmpty()) {
                emptyList()
            } else {
                ParticipantsTable
                    .selectAll()
                    .where {
                        (ParticipantsTable.callId inList live) and
                            (ParticipantsTable.state eq ParticipantState.INVITED.name)
                    }.map { CallId(it[ParticipantsTable.callId]) }
                    .toList()
                    .distinct()
            }
        }

    override suspend fun idleSince(cutoff: Instant): List<CallId> =
        database.tx("calls.idle-since") {
            CallsTable
                .selectAll()
                .where { (CallsTable.state eq CallState.ACTIVE.name) and (CallsTable.activeAt less cutoff) }
                .map { CallId(it[CallsTable.callId]) }
                .toList()
        }

    /** Writes one participant's row and replaces their tracks. */
    private suspend fun writeParticipant(callId: CallId, participant: Participant) {
        ParticipantsTable.upsert(ParticipantsTable.callId, ParticipantsTable.userId) {
            it[this.callId] = callId.value
            it[userId] = participant.userId.value
            it[state] = participant.state.name
            it[sessionId] = participant.sessionId
            it[audio] = participant.audio
            it[video] = participant.video
            it[joinedAt] = participant.joinedAt
            it[updatedAt] = participant.updatedAt
            it[connectedAt] = participant.connectedAt
        }
        TracksTable.deleteWhere {
            (TracksTable.callId eq callId.value) and (TracksTable.userId eq participant.userId.value)
        }
        if (participant.tracks.isNotEmpty()) {
            TracksTable.batchInsert(participant.tracks) { track ->
                this[TracksTable.callId] = callId.value
                this[TracksTable.userId] = participant.userId.value
                this[TracksTable.trackName] = track.trackName
                this[TracksTable.kind] = track.kind
                this[TracksTable.mid] = track.mid
            }
        }
    }

    private suspend fun withParticipants(row: ResultRow): Call {
        val callId = row[CallsTable.callId]
        val tracks =
            TracksTable
                .selectAll()
                .where { TracksTable.callId eq callId }
                .map {
                    UserId(it[TracksTable.userId]) to
                        CallTrack(it[TracksTable.trackName], it[TracksTable.kind], it[TracksTable.mid])
                }.toList()
                .groupBy({ it.first }, { it.second })
        val participants =
            ParticipantsTable
                .selectAll()
                .where { ParticipantsTable.callId eq callId }
                .map { it.toParticipant(tracks) }
                .toList()
                .sortedBy { it.userId.value }
        return row.toCall(participants)
    }

    private fun ResultRow.toParticipant(tracks: Map<UserId, List<CallTrack>>): Participant {
        val userId = UserId(this[ParticipantsTable.userId])
        return Participant(
            userId = userId,
            state = ParticipantState.valueOf(this[ParticipantsTable.state]),
            sessionId = this[ParticipantsTable.sessionId],
            tracks = tracks[userId].orEmpty().sortedBy { it.trackName },
            audio = this[ParticipantsTable.audio],
            video = this[ParticipantsTable.video],
            joinedAt = this[ParticipantsTable.joinedAt],
            updatedAt = this[ParticipantsTable.updatedAt],
            connectedAt = this[ParticipantsTable.connectedAt],
        )
    }

    private fun ResultRow.toCall(participants: List<Participant>) =
        Call(
            callId = CallId(this[CallsTable.callId]),
            chatId = ChatId(this[CallsTable.chatId]),
            kind = CallKind.valueOf(this[CallsTable.kind]),
            state = CallState.valueOf(this[CallsTable.state]),
            startedBy = UserId(this[CallsTable.startedBy]),
            isGroup = this[CallsTable.isGroup],
            createdAt = this[CallsTable.createdAt],
            startedAt = this[CallsTable.startedAt],
            endedAt = this[CallsTable.endedAt],
            endReason = this[CallsTable.endReason]?.let { EndReason.valueOf(it) },
            activeAt = this[CallsTable.activeAt],
            participants = participants,
        )
}

/** Calls' outbox rows, through infrastructure's `writeOutbox`. */
class CallsOutboxImpl(private val database: Database) : CallsOutbox {
    override suspend fun write(events: List<EventEnvelope>) {
        if (events.isEmpty()) return
        database.tx("outbox.write") { events.forEach { database.writeOutbox(it) } }
    }
}
