package com.circaso.architecture

import com.lemonappdev.konsist.api.declaration.KoClassDeclaration
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration

/** A Gradle project, as far as the rules care: its kind and, for business modules, which module. */
data class Project(val kind: String, val module: String? = null) {
    override fun toString() = if (module != null) "$kind($module)" else kind
}

/**
 * The Konsist rules from docs/architecture.md (Module anatomy, Dependency rules, Integrations, Kandra).
 * Each rule takes the main-source files and returns its violations, so the same rule runs on the real
 * project (must find none) and on a deliberately broken fixture (must find some).
 */
object Rules {
    private val reservedPackages =
        setOf("shared", "web", "infrastructure", "integrations", "observability", "gateway", "grpc", "app")
    private val agentReadPrefixes = listOf("get", "list", "find", "count", "summarise")

    // ---- where a file or an import lives -----------------------------------------------------------------

    fun projectOf(file: KoFileDeclaration): Project? {
        val dir = file.path.replace('\\', '/').substringBefore("/src/main/")
        Regex(".*/modules/([a-z]+)/(api|impl)$").matchEntire(dir)?.let {
            return Project("module-${it.groupValues[2]}", it.groupValues[1])
        }
        Regex(".*/(infrastructure|integrations|observability)/(api|impl)$").matchEntire(dir)?.let {
            return Project("${it.groupValues[1]}-${it.groupValues[2]}")
        }
        return when {
            dir.endsWith("/shared-web") -> Project("shared-web")
            dir.endsWith("/shared") -> Project("shared")
            dir.endsWith("/gateway") -> Project("gateway")
            dir.endsWith("/grpc") -> Project("grpc")
            dir.endsWith("/app") -> Project("app")
            else -> null
        }
    }

    /** Which project an import `com.circaso.…` points into. */
    fun projectOfImport(name: String): Project? {
        val parts = name.split('.')
        if (parts.size < 3 || parts[0] != "com" || parts[1] != "circaso") return null
        val first = parts[2]
        val second = parts.getOrNull(3)
        return when (first) {
            "shared" -> {
                Project("shared")
            }

            "web" -> {
                Project("shared-web")
            }

            "gateway" -> {
                Project("gateway")
            }

            "grpc" -> {
                Project("grpc")
            }

            "app" -> {
                Project("app")
            }

            "infrastructure", "integrations", "observability" -> {
                when (second) {
                    "api" -> Project("$first-api")
                    "impl" -> Project("$first-impl")
                    else -> null
                }
            }

            else -> {
                if (second == "api") Project("module-api", first) else Project("module-impl", first)
            }
        }
    }

    /** The dependency table, the same one `build-logic` enforces on Gradle dependencies, here for imports. */
    fun allows(from: Project, to: Project): Boolean {
        if (from == to) return true
        val apis = setOf("infrastructure-api", "integrations-api", "observability-api")
        return when (from.kind) {
            "shared" -> {
                false
            }

            "shared-web" -> {
                to.kind == "shared"
            }

            "infrastructure-api", "integrations-api", "observability-api" -> {
                to.kind == "shared"
            }

            "infrastructure-impl" -> {
                to.kind == "infrastructure-api" || to.kind == "observability-api" ||
                    to.kind == "shared"
            }

            "integrations-impl" -> {
                to.kind in
                    setOf("integrations-api", "infrastructure-api", "observability-api", "shared")
            }

            "observability-impl" -> {
                to.kind == "observability-api" || to.kind == "shared"
            }

            "module-api" -> {
                to.kind == "shared" || (to.kind == "module-api" && to.module != from.module)
            }

            "module-impl" -> {
                to.kind in apis || to.kind == "shared" || to.kind == "shared-web" ||
                    to.kind == "module-api"
            }

            "gateway" -> {
                to.kind in apis || to.kind == "shared" || to.kind == "shared-web" || to.kind == "module-api"
            }

            "grpc" -> {
                to.kind in apis || to.kind == "shared" || to.kind == "module-api"
            }

            "app" -> {
                true
            }

            else -> {
                false
            }
        }
    }

    private fun KoFileDeclaration.pkg(): String = packagee?.name.orEmpty()

    /** `com.circaso.<module>.<layer>.…` → the layer, for business-module files. */
    private fun KoFileDeclaration.layer(): String? {
        val project = projectOf(this)
        if (project?.kind != "module-impl" && project?.kind != "module-api") return null
        return pkg().split('.').getOrNull(3) ?: pkg().split('.').getOrNull(2)
    }

    private fun KoFileDeclaration.subLayer(): String? = pkg().split('.').getOrNull(4)

    private fun KoFileDeclaration.importNames(): List<String> = imports.map { it.name }

    private fun KoFileDeclaration.isPresentation(sub: String): Boolean =
        isBusiness() && layer() == "presentation" && subLayer() == sub

    private fun KoFileDeclaration.isBusiness(): Boolean = projectOf(this)?.kind?.startsWith("module-") == true

    private fun violation(file: KoFileDeclaration, message: String) =
        "${file.path.substringAfter(
            "circaso/",
        )}: $message"

    // ---- the rules ----------------------------------------------------------------------------------------

    /** Nothing in `domain` imports Ktor, Kandra, kotlinx-serialization or any library; only ports and the kernel. */
    fun domainIsPureKotlin(files: List<KoFileDeclaration>): List<String> =
        files
            .filter {
                it.isBusiness() &&
                    it.layer() == "domain"
            }.flatMap { file ->
                val module = projectOf(file)!!.module
                file.importNames().mapNotNull { import ->
                    val allowed =
                        import.startsWith("java.") || import.startsWith("kotlin.") ||
                            import.startsWith("kotlinx.coroutines") ||
                            import.startsWith("com.circaso.shared.") ||
                            import.startsWith("com.circaso.$module.domain.") ||
                            import.startsWith("com.circaso.$module.api.") ||
                            (import.startsWith("com.circaso.") && projectOfImport(import)?.kind == "module-api") ||
                            import.startsWith("com.circaso.integrations.api.")
                    if (allowed) {
                        null
                    } else {
                        violation(
                            file,
                            "domain imports '$import' (domain sees only shared, its own api and domain, other " +
                                "modules' api, integrations:api)",
                        )
                    }
                }
            }

    /** A module's `api` is a contract: no Ktor, Kandra, Koin, and only `shared` and other modules' `api`. */
    fun apiIsContractOnly(files: List<KoFileDeclaration>): List<String> =
        files
            .filter {
                projectOf(it)?.kind ==
                    "module-api"
            }.flatMap { file ->
                file.importNames().mapNotNull { import ->
                    val circaso = projectOfImport(import)
                    val ok =
                        when {
                            import.startsWith("com.circaso.") -> {
                                circaso != null && allows(projectOf(file)!!, circaso)
                            }

                            else -> {
                                import.startsWith("java.") || import.startsWith("kotlin.") ||
                                    import.startsWith("kotlinx.serialization")
                            }
                        }
                    if (ok) null else violation(file, "api imports '$import'")
                }
            }

    /** Request and response DTOs live in `presentation` and never leave it. */
    fun dtosStayInPresentation(files: List<KoFileDeclaration>): List<String> =
        files.filter { it.isBusiness() }.flatMap { file ->
            val inPresentation = file.layer() == "presentation"
            val leaked =
                if (inPresentation) {
                    emptyList()
                } else {
                    file
                        .importNames()
                        .filter {
                            ".presentation." in it &&
                                ".dto." in it
                        }.map { violation(file, "imports DTO '$it' outside presentation") }
                }
            val declared =
                if (inPresentation) {
                    emptyList()
                } else {
                    file
                        .classes()
                        .filter {
                            it.name.endsWith("Request") ||
                                it.name.endsWith("Response")
                        }.map { violation(file, "declares '${it.name}' outside presentation") }
                }
            leaked + declared
        }

    /**
     * Rows live in `data.entities`, end in `Row`, and never leave `data`. `di` is the one other place that names
     * them, to hand each repository its entity; it still may not touch another module's rows.
     */
    fun rowsStayInData(files: List<KoFileDeclaration>): List<String> =
        files.filter { it.isBusiness() }.flatMap { file ->
            val inData = file.layer() == "data"
            val leaked =
                if (inData || file.layer() == "di") {
                    val own = projectOf(file)!!.module
                    file
                        .importNames()
                        .filter { ".data.entities." in it && !it.startsWith("com.circaso.$own.") }
                        .map { violation(file, "reads another module's row '$it'") }
                } else {
                    file.importNames().filter { ".data.entities." in it }.map {
                        violation(
                            file,
                            "references row '$it' outside data",
                        )
                    }
                }
            val misplaced =
                file.classes().flatMap { cls ->
                    when {
                        cls.name.endsWith(
                            "Row",
                        ) && !(inData && file.subLayer() == "entities") -> {
                            listOf(
                                violation(file, "row '${cls.name}' declared outside data.entities"),
                            )
                        }

                        inData && file.subLayer() == "entities" &&
                            !cls.name.endsWith(
                                "Row",
                            )
                        -> {
                            listOf(violation(file, "entity '${cls.name}' must end in Row"))
                        }

                        else -> {
                            emptyList()
                        }
                    }
                }
            leaked + misplaced
        }

    /** Controllers, event handlers and `api` implementations call services only. */
    fun entryPointsCallServices(files: List<KoFileDeclaration>): List<String> =
        files
            .filter {
                it.isBusiness() && it.layer() == "presentation" &&
                    it.subLayer() in setOf("controllers", "events", "api", "agent")
            }.flatMap { file ->
                file
                    .importNames()
                    .filter { ".domain.usecases." in it || ".domain.repositories." in it || ".data." in it }
                    .map { violation(file, "${file.subLayer()} imports '$it'; entry points depend on services only") }
            }

    /** Services call use cases and nothing else; no repositories, rows, DTOs or controllers. */
    fun servicesCallUseCases(files: List<KoFileDeclaration>): List<String> =
        files.filter { it.isPresentation("services") }.flatMap { file ->
            file
                .importNames()
                .filter {
                    ".domain.repositories." in it || ".data." in it || ".presentation.dto." in it ||
                        ".presentation.controllers." in it
                }.map { violation(file, "service imports '$it'; services depend on use cases only") }
        }

    /** One public `suspend operator fun invoke` per use case; no use case calls another, a `*Impl` or `data`. */
    fun useCasesAreSingleOperations(files: List<KoFileDeclaration>): List<String> {
        val useCaseFiles = files.filter { it.isBusiness() && it.layer() == "domain" && it.subLayer() == "usecases" }
        val useCaseNames = useCaseFiles.flatMap { it.classes() }.map { it.name }.toSet()
        return useCaseFiles.flatMap { file ->
            file
                .importNames()
                .filter {
                    ".data." in it ||
                        it.endsWith(
                            "Impl",
                        )
                }.map { violation(file, "use case imports '$it'") } +
                file.classes().flatMap { cls -> useCaseViolations(file, cls, useCaseNames) }
        }
    }

    private fun useCaseViolations(
        file: KoFileDeclaration,
        cls: KoClassDeclaration,
        useCaseNames: Set<String>,
    ): List<String> {
        val publicFunctions = cls.functions().filter { it.hasPublicOrDefaultModifier }
        val single = publicFunctions.singleOrNull()
        val shape =
            if (single == null || single.name != "invoke" || !single.hasOperatorModifier ||
                !single.hasSuspendModifier
            ) {
                listOf(
                    violation(
                        file,
                        "use case '${cls.name}' must have exactly one public 'suspend operator fun invoke' " +
                            "(has ${publicFunctions.map { it.name }})",
                    ),
                )
            } else {
                emptyList()
            }
        val calls =
            (cls.primaryConstructor?.parameters.orEmpty())
                .map { it.type.name }
                .filter {
                    it in useCaseNames &&
                        it != cls.name
                }.map {
                    violation(
                        file,
                        "use case '${cls.name}' takes use case '$it'; share the rule on the model or a domain " +
                            "helper",
                    )
                }
        return shape + calls
    }

    /** Naming by folder: classes in `services` end in Service, in `controllers` Controller, in `events` Handler. */
    fun layersAreNamedByRole(files: List<KoFileDeclaration>): List<String> =
        files.filter { it.isBusiness() && it.layer() == "presentation" }.flatMap { file ->
            val suffix =
                when (file.subLayer()) {
                    "services" -> "Service"
                    "controllers" -> "Controller"
                    "events" -> "Handler"
                    else -> null
                }
            if (suffix == null) {
                emptyList()
            } else {
                file
                    .classes()
                    .filter { !it.name.endsWith(suffix) }
                    .map { violation(file, "'${it.name}' in ${file.subLayer()} must end in $suffix") }
            }
        }

    /**
     * A controller is a function on `Route` that takes the service and holds the path groups, the handlers and their
     * docs (`fun Route.profileController(profiles: ProfileService)`), as in Ktor's own advice; it is not a class.
     */
    fun controllersAreRouteFunctions(files: List<KoFileDeclaration>): List<String> =
        files.filter { it.isPresentation("controllers") }.flatMap { file ->
            val classes =
                file.classes().map {
                    violation(file, "'${it.name}' is a class; a controller is a 'fun Route.xController(service)'")
                }
            val hasController =
                file.functions().any { fn ->
                    fn.receiverType?.name == "Route" && fn.name.endsWith("Controller") &&
                        fn.parameters.any { it.type.name.endsWith("Service") }
                }
            classes +
                if (hasController) {
                    emptyList()
                } else {
                    listOf(
                        violation(file, "no 'fun Route.xController(service: XService)' in a controllers file"),
                    )
                }
        }

    /** Entities name their keyspace (`circaso_<module>`) and are unique across modules. */
    fun entitiesNameTheirKeyspace(files: List<KoFileDeclaration>): List<String> {
        val entities =
            files.filter { it.isBusiness() }.flatMap { file ->
                file
                    .classes()
                    .filter { cls ->
                        cls.annotations.any {
                            it.name ==
                                "ScyllaTable"
                        }
                    }.map { file to it }
            }
        val perEntity =
            entities.flatMap { (file, cls) ->
                val annotation = cls.annotations.first { it.name == "ScyllaTable" }
                val module = projectOf(file)!!.module
                val literal = Regex("keyspace\\s*=\\s*\"([^\"]*)\"").find(annotation.text)?.groupValues?.get(1)
                when {
                    !annotation.text.contains(
                        "keyspace",
                    ) -> {
                        listOf(
                            violation(
                                file,
                                "'${cls.name}' has no keyspace in @ScyllaTable; there is no default keyspace",
                            ),
                        )
                    }

                    literal != null && literal != "circaso_$module" -> {
                        listOf(
                            violation(file, "'${cls.name}' names keyspace '$literal', expected 'circaso_$module'"),
                        )
                    }

                    else -> {
                        emptyList()
                    }
                }
            }
        val duplicates =
            entities
                .groupBy { it.second.name }
                .filter { (_, owners) -> owners.size > 1 }
                .map { (name, owners) ->
                    "entity name '$name' is used by ${owners.map {
                        projectOf(
                            it.first,
                        )!!.module
                    }}; names must be unique across modules (Koin binds by simple name)"
                }
        return perEntity + duplicates
    }

    /** Kandra is touched only inside the `data` layer of a business module. */
    fun kandraOnlyInData(files: List<KoFileDeclaration>): List<String> =
        files.filter { it.isBusiness() && it.layer() != "data" }.flatMap { file ->
            file
                .importNames()
                .filter { it.startsWith("io.kandra") || it.startsWith("com.kandraorm") }
                .map { violation(file, "imports Kandra '$it' outside data") }
        }

    /** Business code reaches telemetry through the `Telemetry` handle, never OpenTelemetry directly. */
    fun noDirectOpenTelemetryInModules(files: List<KoFileDeclaration>): List<String> =
        files.filter { it.isBusiness() }.flatMap { file ->
            file
                .importNames()
                .filter {
                    it.startsWith("io.opentelemetry")
                }.map { violation(file, "imports OpenTelemetry '$it'; use observability:api Telemetry") }
        }

    /** Every import of another project follows the dependency table: modules meet only through `api`. */
    fun importsFollowTheDependencyTable(files: List<KoFileDeclaration>): List<String> =
        files.flatMap { file ->
            val from = projectOf(file) ?: return@flatMap emptyList()
            file.importNames().mapNotNull { import ->
                val to = projectOfImport(import) ?: return@mapNotNull null
                if (allows(
                        from,
                        to,
                    )
                ) {
                    null
                } else {
                    violation(file, "$from imports '$import' ($to); the dependency table forbids it")
                }
            }
        }

    /** One vendor package never imports another; `dev` stands apart too. */
    fun vendorsDoNotKnowEachOther(files: List<KoFileDeclaration>): List<String> =
        files.filter { projectOf(it)?.kind == "integrations-impl" }.flatMap { file ->
            val own = vendorOf(file.pkg())
            file.importNames().filter { it.startsWith("com.circaso.integrations.impl.") }.mapNotNull { import ->
                val other = vendorOf(import.substringBeforeLast('.'))
                if (own != null && other != null &&
                    own != other
                ) {
                    violation(file, "vendor '$own' imports vendor '$other' ('$import')")
                } else {
                    null
                }
            }
        }

    private fun vendorOf(pkg: String): String? {
        val rest =
            pkg
                .removePrefix(
                    "com.circaso.integrations.impl",
                ).removePrefix(".")
                .split('.')
                .filter { it.isNotEmpty() }
        return when {
            rest.isEmpty() -> null
            rest[0] == "dev" -> "dev"
            else -> rest.take(2).joinToString(".")
        }
    }

    /** The assistant's agent controllers call only service methods that read. */
    fun agentControllersOnlyRead(files: List<KoFileDeclaration>): List<String> =
        files.filter { it.isBusiness() && it.layer() == "presentation" && it.subLayer() == "agent" }.flatMap { file ->
            val serviceNames =
                (
                    file.classes().flatMap { cls -> cls.primaryConstructor?.parameters.orEmpty() } +
                        file.functions().flatMap { it.parameters }
                ).filter { it.type.name.endsWith("Service") }.map { it.name }.toSet()
            serviceNames.flatMap { service ->
                Regex("\\b$service\\.(\\w+)")
                    .findAll(file.text)
                    .map { it.groupValues[1] }
                    .filter { method ->
                        agentReadPrefixes.none { method.startsWith(it) }
                    }.map { method ->
                        violation(
                            file,
                            "agent code calls '$service.$method'; agent routes may only call " +
                                "get/list/find/count/summarise methods",
                        )
                    }.toList()
            }
        }

    private val httpMethodWithSlashPath = Regex("""\b(get|post|put|patch|delete|head|options|webSocket)\(\s*"/""")

    /**
     * Routes are grouped by path, as Ktor's "Group by path" advice has it: a parent `route("/order") { … }` owns the
     * path, and each endpoint inside carries only its own segment, with no leading slash (`get("{id}")`, or none at all
     * for the parent's own path), so it inherits what is above it.
     */
    fun routesGroupByPath(files: List<KoFileDeclaration>): List<String> =
        files
            .filter { file ->
                val route =
                    file.isBusiness() && file.layer() == "presentation" &&
                        file.subLayer() in setOf("controllers", "agent")
                route || projectOf(file)?.kind == "gateway"
            }.flatMap { file ->
                httpMethodWithSlashPath
                    .findAll(file.text)
                    .map { match ->
                        violation(
                            file,
                            "'${match.value.trim()}…' starts with a slash; put the shared path in a parent " +
                                "route(\"/…\") and give the endpoint only its own segment",
                        )
                    }.toList()
            }

    private val otherHttpClients =
        listOf(
            "java.net.http",
            "java.net.HttpURLConnection",
            "okhttp3",
            "com.squareup.okhttp",
            "org.apache.http",
            "org.apache.hc",
            "retrofit2",
            "kong.unirest",
        )
    private val ktorClientConstructor = Regex("""(^|[^.\w])HttpClient\(""")

    /**
     * Outbound HTTP is Ktor's client and nothing else, and only `infrastructure:impl` builds one: everything else asks `HttpClients.named("<vendor>")`
     * for the vendor's client, which comes with the timeouts, retries, circuit breaker, tracing and log redaction the architecture promises.
     */
    fun httpOnlyThroughKtor(files: List<KoFileDeclaration>): List<String> =
        files.flatMap { file ->
            val other =
                file
                    .importNames()
                    .filter { import ->
                        otherHttpClients.any {
                            import == it ||
                                import.startsWith("$it.")
                        }
                    }.map {
                        violation(
                            file,
                            "imports '$it'; outbound HTTP uses Ktor's client, through " +
                                "HttpClients.named(\"<vendor>\")",
                        )
                    }
            val builds =
                if (projectOf(file)?.kind != "infrastructure-impl" &&
                    ktorClientConstructor.containsMatchIn(file.text)
                ) {
                    listOf(
                        violation(
                            file,
                            "builds an HttpClient; ask HttpClients.named(\"<vendor>\") instead (only " +
                                "infrastructure:impl builds clients)",
                        ),
                    )
                } else {
                    emptyList()
                }
            other + builds
        }

    /** Hand-built or hand-read JSON, and maps standing in for a payload class (decision 2026-10-27). */
    private val untypedPayloads =
        listOf(
            "Map<String, Any" to "a Map<String, Any> payload",
            "buildJsonObject" to "buildJsonObject",
            "JsonObject(" to "JsonObject(…)",
            ".jsonObject[" to ".jsonObject[…]",
            ".jsonPrimitive" to ".jsonPrimitive",
            "as JsonObject" to "a cast to JsonObject",
            "as? JsonObject" to "a cast to JsonObject",
            "as? JsonPrimitive" to "a cast to JsonPrimitive",
            "parseToJsonElement" to "parseToJsonElement",
            "JsonPrimitive(" to "JsonPrimitive(…)",
            "buildJsonArray" to "buildJsonArray",
            ".jsonArray" to ".jsonArray",
        )

    /**
     * Main-source files that may still build or read JSON by hand, by path under `com/circaso/`, with why. Every
     * other file uses typed `@Serializable` classes.
     */
    val untypedPayloadAllowList: Map<String, String> =
        linkedMapOf(
            // Owned by the parallel slices that convert them. Each entry is removed when MS4, AI1b or WS1 merges.
            "integrations/impl/fcm/FcmPush.kt" to
                "MS4 converts it; FCM's `data` stays a string map, the FCM API requires it, encoded from a typed class",
            "messaging/data/cache/RealtimeFramesImpl.kt" to
                "MS4 converts the messaging frames",
            "messaging/domain/ports/Ports.kt" to
                "MS4 converts the messaging frames",
            "gateway/realtime/GatewayRealtime.kt" to
                "MS4 converts pushFor",
            "integrations/impl/workersai/ChatCompletions.kt" to
                "AI1b converts Workers AI",
            "integrations/impl/workersai/WorkersAiGateway.kt" to
                "AI1b converts Workers AI",
            "integrations/impl/workersai/WorkersAiLanguageModel.kt" to
                "AI1b converts Workers AI",
            "integrations/impl/workersai/WorkersAiLiveSpeech.kt" to
                "AI1b converts Workers AI",
            "integrations/impl/workersai/WorkersAiLiveTranscription.kt" to
                "AI1b converts Workers AI",
            "integrations/impl/workersai/WorkersAiSpeechToText.kt" to
                "AI1b converts Workers AI",
            "integrations/impl/workersai/WorkersAiTextToSpeech.kt" to
                "AI1b converts Workers AI",
            "integrations/impl/workersai/WorkersAiVisionModel.kt" to
                "AI1b converts Workers AI",
            "assistant/data/tools/HttpAgentTools.kt" to
                "AI1b converts Asha's tools",
            "assistant/presentation/controllers/LiveVoiceController.kt" to
                "AI1b converts the live-voice frames",
            // Not payloads, so they stay.
            "gateway/ApiDocsCatalog.kt" to
                "the OpenAPI generator builds a JSON schema document",
            "infrastructure/impl/grpc/PeerGrpcClients.kt" to
                "grpc-java takes its retry service config as a Map<String, Any>",
            "infrastructure/impl/logging/LineFormatter.kt" to
                "a log line's fields, read from logback, not a payload",
            "integrations/impl/social/IdTokenClaimShapes.kt" to
                "JsonTransformingSerializers reshape `aud` and `email_verified` before the typed claims read them",
        )

    /**
     * Every payload is a typed `@Serializable` data class: no `Map<String, Any>`, `buildJsonObject`, `JsonObject(…)`,
     * `.jsonObject[…]` or `.jsonPrimitive` in main sources outside [untypedPayloadAllowList] (decision 2026-10-27).
     */
    fun payloadsAreTyped(files: List<KoFileDeclaration>): List<String> =
        files
            .filter { file ->
                val path = file.path.replace('\\', '/')
                untypedPayloadAllowList.keys.none { path.endsWith("/$it") }
            }.flatMap { file ->
                untypedPayloadUses(file).map { what ->
                    violation(
                        file,
                        "uses $what; payloads are typed @Serializable data classes (decode and encode with their " +
                            "serializer)",
                    )
                }
            }

    /** What untyped JSON or payload maps [file] uses, whether or not it is allowed to. */
    fun untypedPayloadUses(file: KoFileDeclaration): List<String> =
        untypedPayloads.filter { (pattern, _) -> pattern in file.text }.map { (_, what) -> what }

    val all: Map<String, (List<KoFileDeclaration>) -> List<String>> =
        linkedMapOf(
            "domainIsPureKotlin" to ::domainIsPureKotlin,
            "apiIsContractOnly" to ::apiIsContractOnly,
            "dtosStayInPresentation" to ::dtosStayInPresentation,
            "rowsStayInData" to ::rowsStayInData,
            "entryPointsCallServices" to ::entryPointsCallServices,
            "servicesCallUseCases" to ::servicesCallUseCases,
            "useCasesAreSingleOperations" to ::useCasesAreSingleOperations,
            "layersAreNamedByRole" to ::layersAreNamedByRole,
            "entitiesNameTheirKeyspace" to ::entitiesNameTheirKeyspace,
            "kandraOnlyInData" to ::kandraOnlyInData,
            "noDirectOpenTelemetryInModules" to ::noDirectOpenTelemetryInModules,
            "importsFollowTheDependencyTable" to ::importsFollowTheDependencyTable,
            "vendorsDoNotKnowEachOther" to ::vendorsDoNotKnowEachOther,
            "agentControllersOnlyRead" to ::agentControllersOnlyRead,
            "routesGroupByPath" to ::routesGroupByPath,
            "controllersAreRouteFunctions" to ::controllersAreRouteFunctions,
            "httpOnlyThroughKtor" to ::httpOnlyThroughKtor,
            "payloadsAreTyped" to ::payloadsAreTyped,
        )
}
