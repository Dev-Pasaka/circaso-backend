package com.circaso.assistant.domain.support

import com.circaso.assistant.domain.ports.BlockKind
import com.circaso.assistant.domain.ports.ReplyBlocks

/**
 * Sits between the model's stream and the user (decision 2026-10-31). Ordinary text goes straight through, except the
 * start of a line that may be opening a fence, held for the few characters it takes to tell. A ```chart,
 * ```statement or ```diagram block is held whole until its closing fence, checked by [ReplyBlocks] and sent at once,
 * repaired, or not at all, so the app never receives half a block or a broken one. Other fences (code) pass through.
 *
 * In a spoken turn ([isVoice]) the blocks are dropped (they can't be said) and the markdown marks a voice would read
 * out (`*`, `` ` ``, a heading's `#`) are taken out.
 */
class BlockFilter(private val blocks: ReplyBlocks, private val isVoice: Boolean) {
    private val held = StringBuilder()
    private var block: BlockKind? = null
    private var inCode = false
    private var atLineStart = true

    /** Takes the next piece the model wrote; what may be shown now. */
    fun feed(delta: String): String {
        held.append(delta)
        return drain(isFinal = false)
    }

    /** The model has finished the step: whatever is held, settled. */
    fun finish(): String {
        val out = drain(isFinal = true)
        val open = block ?: return out + speakable(takeHeld())
        block = null
        return out + checked(open, takeHeld().removeSuffix(FENCE).trimEnd())
    }

    private fun drain(isFinal: Boolean): String {
        val out = StringBuilder()
        while (true) {
            val progressed = if (block == null) outside(out, isFinal) else inside(out)
            if (!progressed) return out.toString()
        }
    }

    /** Outside a block: emits up to the first line that is, or may become, a fence. False when it must wait. */
    private fun outside(out: StringBuilder, isFinal: Boolean): Boolean {
        var lineStart = if (atLineStart) 0 else nextLineStart(0)
        while (lineStart in 0..held.length) {
            val end = held.indexOf('\n', lineStart)
            val line = held.substring(lineStart, if (end < 0) held.length else end).trimStart(' ')
            if (end < 0) {
                if (!isFinal && mayBeMarkup(line)) {
                    out.append(speakable(held.substring(0, lineStart)))
                    held.delete(0, lineStart)
                    atLineStart = true
                    return false
                }
                break
            }
            if (line.startsWith(FENCE)) {
                val kind = if (inCode) null else BlockKind.of(line.removePrefix(FENCE))
                if (kind != null) {
                    out.append(speakable(held.substring(0, lineStart)))
                    held.delete(0, end + 1)
                    block = kind
                    atLineStart = true
                    return true
                }
                inCode = !inCode
            }
            lineStart = end + 1
        }
        if (isFinal) return false
        out.append(speakable(takeHeld()))
        atLineStart = out.endsWith("\n") || (out.isEmpty() && atLineStart)
        return false
    }

    /** Inside a block: waits for the closing fence on a line of its own, then sends the block checked. */
    private fun inside(out: StringBuilder): Boolean {
        var lineStart = 0
        while (true) {
            val end = held.indexOf('\n', lineStart)
            if (end < 0) return false
            if (held.substring(lineStart, end).trim() == FENCE) {
                val body = held.substring(0, lineStart).trimEnd()
                held.delete(0, end + 1)
                out.append(checked(block!!, body))
                block = null
                atLineStart = true
                return true
            }
            lineStart = end + 1
        }
    }

    /**
     * Whether an unfinished line may still turn out to be a fence, or in a spoken reply a heading (whose `#` marks
     * are taken out once the line shows its first word).
     */
    private fun mayBeMarkup(line: String): Boolean =
        FENCE.startsWith(line) || line.startsWith(FENCE) || (isVoice && line.all { it == '#' || it == ' ' })

    private fun checked(kind: BlockKind, body: String): String {
        if (isVoice) return ""
        val clean = blocks.clean(kind, body) ?: return ""
        return "$FENCE${kind.fence}\n$clean\n$FENCE\n"
    }

    private fun nextLineStart(from: Int): Int {
        val newline = held.indexOf('\n', from)
        return if (newline < 0) -1 else newline + 1
    }

    private fun takeHeld(): String = held.toString().also { held.clear() }

    private fun speakable(text: String): String =
        if (!isVoice) {
            text
        } else {
            text
                .replace(HEADING, "")
                .replace("*", "")
                .replace("`", "")
        }

    private companion object {
        const val FENCE = "```"
        val HEADING = Regex("(?m)^\\s{0,3}#{1,6}\\s+")
    }
}
