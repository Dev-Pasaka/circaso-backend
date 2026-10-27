package com.circaso.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import kotlin.test.Test
import kotlin.test.assertTrue

/** Runs every rule on the real sources. A violation here fails the build. */
class ArchitectureTest {
    private val files: List<KoFileDeclaration> =
        Konsist
            .scopeFromProject()
            .files
            .filter { "/src/main/" in it.path && "/architecture-tests/" !in it.path }

    @Test
    fun `the scan sees the sources it is meant to guard`() {
        val projects = files.mapNotNull { Rules.projectOf(it)?.kind }.toSet()

        assertTrue(files.isNotEmpty(), "Konsist found no main sources; the rules would pass on nothing")
        assertTrue("shared" in projects && "shared-web" in projects && "app" in projects, "projects seen: $projects")
    }

    @Test
    fun `every file allowed untyped payloads exists and still needs the exception`() {
        val stale =
            Rules.untypedPayloadAllowList.keys.filter { allowed ->
                val file = files.firstOrNull { it.path.replace('\\', '/').endsWith("/$allowed") }
                file == null || Rules.untypedPayloadUses(file).isEmpty()
            }

        assertTrue(stale.isEmpty(), "Remove these from Rules.untypedPayloadAllowList:\n" + stale.joinToString("\n"))
    }

    @Test
    fun `every rule passes on the real project`() {
        val violations = Rules.all.flatMap { (name, rule) -> rule(files).map { "[$name] $it" } }

        assertTrue(violations.isEmpty(), "Architecture rules broken:\n" + violations.joinToString("\n") { " - $it" })
    }
}
