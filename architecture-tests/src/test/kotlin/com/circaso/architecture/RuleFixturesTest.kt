package com.circaso.architecture

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A rule that has never failed proves nothing, so each one runs on a deliberately broken slice in
 * `src/test/fixtures/<rule>` and must flag it, and a well-formed slice in `fixtures/_clean` must pass them all.
 * The fixtures are not compiled; Konsist only parses them.
 */
class RuleFixturesTest {
    private fun filesIn(case: String): List<KoFileDeclaration> {
        val dir = File(System.getProperty("user.dir"), "src/test/fixtures/$case")
        assertTrue(dir.isDirectory, "missing fixture $dir")
        // Konsist resolves a directory against the root of the Gradle build, not this module.
        return Konsist.scopeFromDirectory("architecture-tests/src/test/fixtures/$case").files.filter {
            "/src/main/" in
                it.path
        }
    }

    private fun assertFlags(rule: String, vararg expected: String) {
        val violations = Rules.all.getValue(rule)(filesIn(rule))

        assertTrue(violations.isNotEmpty(), "$rule found nothing in its fixture")
        expected.forEach { fragment ->
            assertTrue(
                violations.any {
                    fragment in it
                },
                "$rule should mention '$fragment' but said:\n${violations.joinToString("\n")}",
            )
        }
    }

    @Test
    fun `the clean slice passes every rule`() {
        val files = filesIn("_clean")
        assertTrue(files.size >= 8, "the clean slice should cover every layer, found ${files.size} files")

        val violations = Rules.all.flatMap { (name, rule) -> rule(files).map { "[$name] $it" } }

        assertEquals(emptyList(), violations)
    }

    @Test
    fun `domain may not import Ktor, serialization or Kandra`() =
        assertFlags(
            "domainIsPureKotlin",
            "io.ktor.http.HttpStatusCode",
            "kotlinx.serialization.Serializable",
            "com.kandraorm",
        )

    @Test
    fun `an api may not import a library or an impl`() =
        assertFlags("apiIsContractOnly", "com.kandraorm", "com.circaso.users.domain")

    @Test
    fun `a DTO may not leave presentation`() =
        assertFlags("dtosStayInPresentation", "presentation.dto.SignInRequest", "declares 'SignInResponse'")

    /** Decision A (2026-10-27, AI1b review): only a consumed `*Response`, and only in a module's api project. */
    @Test
    fun `a module's api project may declare a response another module consumes, and nothing else`() {
        assertFlags(
            "dtosStayInPresentation",
            "declares 'AgentSearchRequest'",
            "declares 'LeakedSessionResponse'",
            "presentation.dto.SessionRequest",
        )
        val violations = Rules.all.getValue("dtosStayInPresentation")(filesIn("dtosStayInPresentation"))
        assertTrue(violations.none { "'AgentContactsResponse'" in it }, violations.joinToString("\n"))
    }

    @Test
    fun `a row may not leave data`() =
        assertFlags("rowsStayInData", "AccountRow", "must end in Row", "another module's row")

    @Test
    fun `controllers may not call use cases or repositories`() =
        assertFlags("entryPointsCallServices", "usecases.UpdateProfile", "repositories.ProfileRepository")

    @Test
    fun `services may not call repositories`() =
        assertFlags("servicesCallUseCases", "repositories.ProfileRepository", "presentation.dto")

    @Test
    fun `a use case has one invoke and calls no other use case`() =
        assertFlags("useCasesAreSingleOperations", "exactly one public", "takes use case 'ClaimHandle'", "data.")

    @Test
    fun `classes are named by their folder`() =
        assertFlags(
            "layersAreNamedByRole",
            "'ProfileManager' in services must end in Service",
            "'Profiles' in controllers must end in Controller",
        )

    @Test
    fun `entities name their keyspace and are unique`() =
        assertFlags(
            "entitiesNameTheirKeyspace",
            "no keyspace",
            "expected 'circaso_identity'",
            "must be unique across modules",
        )

    @Test
    fun `Kandra stays in data`() = assertFlags("kandraOnlyInData", "io.kandra.runtime.KandraRepository")

    @Test
    fun `modules do not import OpenTelemetry`() = assertFlags("noDirectOpenTelemetryInModules", "io.opentelemetry")

    @Test
    fun `modules meet only through api`() =
        assertFlags(
            "importsFollowTheDependencyTable",
            "com.circaso.users.domain.models.Profile",
            "com.circaso.infrastructure.impl",
            "gateway imports",
        )

    @Test
    fun `one vendor does not import another`() =
        assertFlags(
            "vendorsDoNotKnowEachOther",
            "sms.africastalking",
            "email.resend",
        )

    @Test
    fun `agent routes only call methods that read`() =
        assertFlags(
            "agentControllersOnlyRead",
            "profiles.update",
            "profiles.delete",
        )

    @Test
    fun `endpoints carry their own segment and inherit the rest from a parent route`() =
        assertFlags("routesGroupByPath", "BadRoutes.kt", "get(\"/", "post(\"/")

    @Test
    fun `outbound HTTP is Ktor's client, from HttpClients, and nothing else`() =
        assertFlags("httpOnlyThroughKtor", "imports 'java.net.http.HttpClient'", "builds an HttpClient")

    @Test
    fun `payloads are typed classes, not maps or hand-built JSON`() =
        assertFlags(
            "payloadsAreTyped",
            "a Map<String, Any> payload",
            "buildJsonObject",
            "JsonObject(…)",
            ".jsonObject[…]",
            ".jsonPrimitive",
            "a cast to JsonObject",
            "a cast to JsonPrimitive",
            "parseToJsonElement",
            "JsonPrimitive(…)",
            "buildJsonArray",
            ".jsonArray",
            "JsonObject.serializer()",
            "JsonElement.serializer()",
            "decodeFromString<JsonObject>",
            "decodeJsonElement",
            "encodeJsonElement",
            "a value declared as JsonObject",
            "a value declared as JsonElement",
        )

    @Test
    fun `an Exposed table lives in its module's data, and only that data reads it`() =
        assertFlags(
            "tablesStayInTheirModule",
            "table 'SessionsTable' declared outside data.tables",
            "table object 'Devices' must end in Table",
            "references table 'com.circaso.identity.data.tables.AccountsTable' outside data",
            "imports Exposed 'org.jetbrains.exposed.v1.jdbc.selectAll' outside data",
            "references another module's table 'com.circaso.identity.data.tables.AccountsTable'",
        )

    @Test
    fun `modules reach the database only through Database tx`() =
        assertFlags(
            "noWayInButDatabaseTx",
            "opens a raw Exposed transaction",
            "calls Database.connect",
            "imports 'org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction'",
            "uses SchemaUtils",
        )

    @Test
    fun `JDBC and Flyway stay in the Migrate code`() =
        assertFlags(
            "jdbcOnlyInMigrate",
            "imports 'java.sql.Connection'",
            "imports 'org.jetbrains.exposed.v1.jdbc.selectAll'",
            "imports 'org.flywaydb.core.Flyway'",
        )

    @Test
    fun `a transaction is named by a string literal`() {
        assertFlags(
            "operationsAreNamedLiterals",
            "VerifyEmail.kt: 'transactor.run(",
            "EmailRepositoryImpl.kt: 'db.tx(",
        )
        val violations = Rules.all.getValue("operationsAreNamedLiterals")(filesIn("operationsAreNamedLiterals"))
        assertTrue(violations.none { "EmailTransactor" in it }, violations.joinToString("\n"))
        assertEquals(2, violations.size, violations.joinToString("\n"))
    }

    @Test
    fun `a Database handle is named database`() {
        assertFlags("databaseHandlesAreNamedDatabase", "Database handle 'db'", "Database handle 'store'")
        val violations =
            Rules.all.getValue("databaseHandlesAreNamedDatabase")(filesIn("databaseHandlesAreNamedDatabase"))
        assertEquals(2, violations.size, violations.joinToString("\n"))
    }

    @Test
    fun `a module's tests reach infrastructure only through its test fixtures`() {
        val tests =
            Konsist
                .scopeFromDirectory(
                    "architecture-tests/src/test/fixtures/moduleTestsUseOnlyTheFixtures",
                ).files
                .filter {
                    "/src/test/" in it.path
                }
        val violations = Rules.testRules.getValue("moduleTestsUseOnlyTheFixtures")(tests)

        assertTrue(tests.size == 2, "fixture files: ${tests.map { it.name }}")
        assertEquals(2, violations.size, violations.joinToString("\n"))
        assertTrue(violations.all { "ReachesThePools.kt" in it }, violations.joinToString("\n"))
        assertTrue(violations.any { "com.circaso.infrastructure.impl.database.PostgresPools" in it })
    }

    @Test
    fun `nothing blocks a thread on runBlocking`() = assertFlags("noRunBlocking", "Blocking.kt: calls runBlocking")

    @Test
    fun `a Transactor run block holds database work only`() {
        assertFlags(
            "transactionsHoldDatabaseWorkOnly",
            "uses 'events' (EventBus) inside transactor.run",
            "uses 'sms' (SmsGateway)",
            "uses 'reach' (ReachPolicy)",
        )
        val violations =
            Rules.all.getValue("transactionsHoldDatabaseWorkOnly")(filesIn("transactionsHoldDatabaseWorkOnly"))
        assertTrue(violations.none { "'accounts'" in it }, "a repository call is what the block is for")
    }

    @Test
    fun `a controller is a function on Route, not a class`() =
        assertFlags("controllersAreRouteFunctions", "'ProfileController' is a class", "no 'fun Route.xController")
}
