package com.circaso.assistant.domain.ports

/**
 * The rich blocks Asha may put in a text reply (decision 2026-10-31): fenced ```chart, ```statement and ```diagram
 * blocks holding JSON, which the app draws natively. The model can write them wrong, so every block is checked
 * before it leaves the server: repaired to the documented shape where that is safe, or dropped. Never sent broken.
 */
fun interface ReplyBlocks {
    /** The block's body as it may be sent (canonical JSON of the documented shape), or null to drop the block. */
    fun clean(kind: BlockKind, body: String): String?
}

enum class BlockKind(val fence: String) {
    CHART("chart"),
    STATEMENT("statement"),
    DIAGRAM("diagram"),
    ;

    companion object {
        fun of(info: String): BlockKind? = entries.firstOrNull { it.fence == info.trim().lowercase() }
    }
}
