package com.circaso.build

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.ProjectDependency

/** What a Gradle project is in the architecture, read from its path. */
sealed interface Kind {
    data object Shared : Kind
    data object SharedWeb : Kind
    data object InfrastructureApi : Kind
    data object InfrastructureImpl : Kind
    data object IntegrationsApi : Kind
    data object IntegrationsImpl : Kind
    data object ObservabilityApi : Kind
    data object ObservabilityImpl : Kind
    data object Gateway : Kind
    data object Grpc : Kind
    data object App : Kind
    data class ModuleApi(val module: String) : Kind
    data class ModuleImpl(val module: String) : Kind
}

/**
 * The dependency table from docs/architecture.md (Module anatomy → Dependency rules) as code.
 * Applied by every convention plugin at configuration time, so a forbidden dependency fails the build
 * before anything compiles. The Konsist tests in `architecture-tests` are the second guard, on imports.
 */
object DependencyRules {

    fun kindOf(path: String): Kind? {
        val modulePath = Regex("^:modules:([a-z]+):(api|impl)$").matchEntire(path)
        return when {
            modulePath != null -> {
                val (module, layer) = modulePath.destructured
                if (layer == "api") Kind.ModuleApi(module) else Kind.ModuleImpl(module)
            }
            path == ":shared" -> Kind.Shared
            path == ":shared-web" -> Kind.SharedWeb
            path == ":infrastructure:api" -> Kind.InfrastructureApi
            path == ":infrastructure:impl" -> Kind.InfrastructureImpl
            path == ":integrations:api" -> Kind.IntegrationsApi
            path == ":integrations:impl" -> Kind.IntegrationsImpl
            path == ":observability:api" -> Kind.ObservabilityApi
            path == ":observability:impl" -> Kind.ObservabilityImpl
            path == ":gateway" -> Kind.Gateway
            path == ":grpc" -> Kind.Grpc
            path == ":app" -> Kind.App
            else -> null
        }
    }

    /** May a project of kind [from] depend on a project of kind [to]? */
    fun allows(from: Kind, to: Kind): Boolean = when (from) {
        Kind.Shared -> false
        Kind.SharedWeb -> to == Kind.Shared
        Kind.InfrastructureApi -> to == Kind.Shared
        Kind.InfrastructureImpl ->
            to == Kind.InfrastructureApi || to == Kind.ObservabilityApi || to == Kind.Shared
        Kind.IntegrationsApi -> to == Kind.Shared
        Kind.IntegrationsImpl ->
            to == Kind.IntegrationsApi || to == Kind.InfrastructureApi || to == Kind.ObservabilityApi || to == Kind.Shared
        Kind.ObservabilityApi -> to == Kind.Shared
        Kind.ObservabilityImpl -> to == Kind.ObservabilityApi || to == Kind.Shared
        is Kind.ModuleApi -> to == Kind.Shared || (to is Kind.ModuleApi && to.module != from.module)
        is Kind.ModuleImpl -> when (to) {
            Kind.Shared, Kind.SharedWeb, Kind.InfrastructureApi, Kind.IntegrationsApi, Kind.ObservabilityApi -> true
            is Kind.ModuleApi -> true
            else -> false
        }
        Kind.Gateway -> when (to) {
            Kind.Shared, Kind.SharedWeb, Kind.InfrastructureApi, Kind.IntegrationsApi, Kind.ObservabilityApi -> true
            is Kind.ModuleApi -> true
            else -> false
        }
        // The gRPC edge, like the gateway: every `api`, never an `impl`. It is not a web layer, so no `shared-web`.
        Kind.Grpc -> when (to) {
            Kind.Shared, Kind.InfrastructureApi, Kind.IntegrationsApi, Kind.ObservabilityApi -> true
            is Kind.ModuleApi -> true
            else -> false
        }
        Kind.App -> true
    }

    /**
     * `shared`, every module `api` and `integrations:api` carry no library beyond the Kotlin standard
     * library and kotlinx-serialization: no Ktor, no Kandra, no Koin, no vendor SDK.
     */
    private fun libraryAllowed(from: Kind, group: String?, name: String): Boolean {
        val bare = from == Kind.Shared || from == Kind.IntegrationsApi || from is Kind.ModuleApi
        if (!bare) return true
        return group == "org.jetbrains.kotlin" ||
            (group == "org.jetbrains.kotlinx" && name.startsWith("kotlinx-serialization")) ||
            (group == "org.jetbrains" && name == "annotations")
    }

    fun verify(project: Project) {
        val from = kindOf(project.path) ?: return
        val problems = mutableListOf<String>()
        project.configurations.forEach { configuration ->
            val isTest = configuration.name.startsWith("test")
            configuration.dependencies.forEach { dependency ->
                when (dependency) {
                    is ProjectDependency -> {
                        val to = kindOf(dependency.path)
                        if (to != null && !allows(from, to)) {
                            problems += "${project.path} may not depend on ${dependency.path} (configuration " +
                                "'${configuration.name}')"
                        }
                    }
                    is ExternalModuleDependency -> {
                        if (!isTest && !libraryAllowed(from, dependency.group, dependency.name)) {
                            problems += "${project.path} may not depend on library " +
                                "${dependency.group}:${dependency.name} " +
                                "(configuration '${configuration.name}'); it carries only the Kotlin standard " +
                                "library and kotlinx-serialization"
                        }
                    }
                }
            }
        }
        if (problems.isNotEmpty()) {
            throw GradleException(
                "Dependency rules broken (docs/architecture.md → Dependency rules):\n" +
                    problems.joinToString("\n") { " - $it" },
            )
        }
    }
}
