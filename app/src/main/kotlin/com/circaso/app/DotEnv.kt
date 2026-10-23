package com.circaso.app

import java.io.File

/**
 * Local development settings from a `.env` file, so a developer sets variables and secrets in one place instead of
 * exporting them in every terminal. The file is looked for in the working directory and the three above it (Gradle
 * runs the app from `app/`, the repository root is its parent).
 *
 * A variable that is really set in the environment wins over the file, so a deploy is never changed by a stray
 * `.env`; nothing is read in production unless the file is shipped there, which it should not be.
 *
 * The values are also set as system properties, because the logging setup (logback and the console colors) reads them
 * from there, and it starts before the app's own settings are built.
 */
object DotEnv {

    /** Loads the first `.env` found; returns the settings the app should use: the file, overridden by the real environment. */
    fun environment(startDir: File = File(System.getProperty("user.dir")), env: Map<String, String> = System.getenv()): Map<String, String> {
        val values = read(startDir)
        values.forEach { (name, value) -> if (env[name] == null && System.getProperty(name) == null) System.setProperty(name, value) }
        return values + env
    }

    /** The settings of the first `.env` found, or none. */
    fun read(startDir: File): Map<String, String> {
        val file = generateSequence(startDir.absoluteFile) { it.parentFile }.take(4).map { File(it, ".env") }.firstOrNull { it.isFile }
            ?: return emptyMap()
        return parse(file.readLines()).toMap()
    }

    /** `NAME=value` lines; `#` comments, blank lines, an optional `export `, and single or double quotes are understood. */
    fun parse(lines: List<String>): List<Pair<String, String>> = lines.mapNotNull { raw ->
        val line = raw.trim().removePrefix("export ").trim()
        if (line.isEmpty() || line.startsWith("#") || '=' !in line) return@mapNotNull null
        val name = line.substringBefore('=').trim()
        if (!Regex("[A-Za-z_][A-Za-z0-9_]*").matches(name)) return@mapNotNull null
        var value = line.substringAfter('=').trim()
        value = when {
            value.length >= 2 && value.first() == '"' && value.last() == '"' -> value.substring(1, value.length - 1)
            value.length >= 2 && value.first() == '\'' && value.last() == '\'' -> value.substring(1, value.length - 1)
            else -> value.substringBefore(" #").trim() // an unquoted value ends at a trailing comment
        }
        if (value.isEmpty()) null else name to value
    }
}
