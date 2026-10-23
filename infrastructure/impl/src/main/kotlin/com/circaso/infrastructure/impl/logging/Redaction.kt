package com.circaso.infrastructure.impl.logging

/**
 * The last-line filter (Logging → Privacy): masks anything shaped like an E.164 number or a JWT, in case one
 * slips into a message. The same two patterns are in `logback.xml` for the JSON format.
 */
object Redaction {
    const val E164_PATTERN = "\\+[1-9][0-9]{6,14}"
    const val JWT_PATTERN = "eyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*"

    private val e164 = Regex(E164_PATTERN)
    private val jwt = Regex(JWT_PATTERN)

    fun mask(text: String): String = text
        .replace(jwt, "[jwt]")
        .replace(e164) { match ->
            val digits = match.value
            "+${digits.drop(1).take(3)}•••${digits.takeLast(3)}"
        }
}
