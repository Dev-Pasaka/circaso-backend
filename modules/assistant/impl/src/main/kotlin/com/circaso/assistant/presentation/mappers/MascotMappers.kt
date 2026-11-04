package com.circaso.assistant.presentation.mappers

import com.circaso.assistant.domain.models.MascotHints
import com.circaso.assistant.domain.models.ToolStatus
import com.circaso.assistant.presentation.dto.LiveMascotFrame
import com.circaso.assistant.presentation.dto.LiveToolDoneFrame
import com.circaso.assistant.presentation.dto.LiveToolStatusFrame
import com.circaso.assistant.presentation.dto.MascotEvent
import com.circaso.assistant.presentation.dto.ToolDoneEvent
import com.circaso.assistant.presentation.dto.ToolStatusEvent

// The mascot's hints on the wire (decision 2026-11-04): lowercase names from the fixed lists, the same on A2 and A11.

fun MascotHints.toEvent() = MascotEvent(scene.wire, mood.wire, activity.wire, reaction?.wire, sensitive)

fun MascotHints.toFrame() = LiveMascotFrame(scene.wire, mood.wire, activity.wire, reaction?.wire, sensitive)

fun ToolStatus.toStatusEvent() =
    ToolStatusEvent(
        turnId.toString(),
        callId,
        tool,
        label,
        scene = mascot?.scene?.wire,
        mood = mascot?.mood?.wire,
        activity = mascot?.activity?.wire,
        reaction = mascot?.reaction?.wire,
        sensitive = mascot?.sensitive,
    )

fun ToolStatus.toDoneEvent(isOk: Boolean) =
    ToolDoneEvent(
        turnId.toString(),
        callId,
        tool,
        isOk,
        scene = mascot?.scene?.wire,
        mood = mascot?.mood?.wire,
        activity = mascot?.activity?.wire,
        reaction = mascot?.reaction?.wire,
        sensitive = mascot?.sensitive,
    )

fun ToolStatus.toStatusFrame() =
    LiveToolStatusFrame(
        turnId.toString(),
        callId,
        tool,
        label,
        scene = mascot?.scene?.wire,
        mood = mascot?.mood?.wire,
        activity = mascot?.activity?.wire,
        reaction = mascot?.reaction?.wire,
        sensitive = mascot?.sensitive,
    )

fun ToolStatus.toDoneFrame(isOk: Boolean) =
    LiveToolDoneFrame(
        turnId.toString(),
        callId,
        tool,
        isOk,
        scene = mascot?.scene?.wire,
        mood = mascot?.mood?.wire,
        activity = mascot?.activity?.wire,
        reaction = mascot?.reaction?.wire,
        sensitive = mascot?.sensitive,
    )
