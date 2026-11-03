package com.circaso.status.domain.support

/** The bytes a base64 text decodes to, without decoding it (the mapper has already checked it is base64). */
fun decodedSize(base64: String): Int {
    val padding = base64.takeLast(2).count { it == '=' }
    return base64.length / 4 * 3 - padding
}
