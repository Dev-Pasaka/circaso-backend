package com.circaso.ops.domain.support

/** A reason is 3 to 500 characters once trimmed; it is kept in the audit log, so it is a sentence, not an essay. */
object Reasons {
    fun check(raw: String): String? = raw.trim().takeIf { it.length in 3..500 }
}
