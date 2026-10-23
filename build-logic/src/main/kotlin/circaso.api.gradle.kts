import com.circaso.build.DependencyRules
import com.circaso.build.Kind

// The contract side of a module: `modules:<name>:api`, `infrastructure:api`, `integrations:api`,
// `observability:api`. Depends only on `shared` and other modules' `api`; no Ktor, no Kandra, no Koin
// (see DependencyRules).
plugins {
    id("circaso.kotlin")
    `java-library`
}

check(
    DependencyRules.kindOf(path).let {
        it is Kind.ModuleApi || it == Kind.InfrastructureApi || it == Kind.IntegrationsApi || it == Kind.ObservabilityApi
    },
) {
    "circaso.api is for modules:<name>:api, infrastructure:api, integrations:api and observability:api, not $path"
}

dependencies {
    api(project(":shared"))
}
