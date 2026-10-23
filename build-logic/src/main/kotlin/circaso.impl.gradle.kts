import com.circaso.build.DependencyRules
import com.circaso.build.Kind

// The hidden side: `modules:<name>:impl`, `infrastructure:impl`, `integrations:impl`, `observability:impl`.
// Nobody but `app` may depend on it; what it may depend on is in DependencyRules.
plugins {
    id("circaso.kotlin")
    `java-library`
}

when (val kind = DependencyRules.kindOf(path)) {
    is Kind.ModuleImpl -> dependencies {
        implementation(project(":modules:${kind.module}:api"))
        implementation(project(":shared"))
        implementation(project(":shared-web"))
        implementation(project(":infrastructure:api"))
        implementation(project(":integrations:api"))
    }
    Kind.InfrastructureImpl -> dependencies {
        implementation(project(":infrastructure:api"))
        implementation(project(":shared"))
    }
    Kind.IntegrationsImpl -> dependencies {
        implementation(project(":integrations:api"))
        implementation(project(":infrastructure:api"))
        implementation(project(":shared"))
    }
    Kind.ObservabilityImpl -> dependencies {
        implementation(project(":observability:api"))
        implementation(project(":shared"))
    }
    else -> error("circaso.impl is for modules:<name>:impl, infrastructure:impl, integrations:impl and observability:impl, not $path")
}
