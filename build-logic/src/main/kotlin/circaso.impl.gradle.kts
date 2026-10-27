import com.circaso.build.DependencyRules
import com.circaso.build.Kind
import org.jetbrains.exposed.v1.gradle.plugin.ExposedExtension
import org.jetbrains.exposed.v1.gradle.plugin.MigrationsExtension
import org.jetbrains.exposed.v1.plugin.core.migration.VersionFormat

// The hidden side: `modules:<name>:impl`, `infrastructure:impl`, `integrations:impl`, `observability:impl`.
// Nobody but `app` may depend on it; what it may depend on is in DependencyRules.
plugins {
    id("circaso.kotlin")
    `java-library`
}

when (val kind = DependencyRules.kindOf(path)) {
    is Kind.ModuleImpl -> {
        dependencies {
            implementation(project(":modules:${kind.module}:api"))
            implementation(project(":shared"))
            implementation(project(":shared-web"))
            implementation(project(":infrastructure:api"))
            implementation(project(":integrations:api"))
        }
        draftMigrationsWithExposed(kind.module)
    }

    Kind.InfrastructureImpl -> {
        dependencies {
            implementation(project(":infrastructure:api"))
            implementation(project(":shared"))
        }
    }

    Kind.IntegrationsImpl -> {
        dependencies {
            implementation(project(":integrations:api"))
            implementation(project(":infrastructure:api"))
            implementation(project(":shared"))
        }
    }

    Kind.ObservabilityImpl -> {
        dependencies {
            implementation(project(":observability:api"))
            implementation(project(":shared"))
        }
    }

    else -> {
        error(
            "circaso.impl is for modules:<name>:impl, infrastructure:impl, integrations:impl and observability:impl, not $path",
        )
    }
}

/**
 * `./gradlew :modules:<name>:impl:generateMigrations` drafts the module's next Flyway script from its `Table` objects
 * (decision 2026-10-27, open question 7): it starts a throw-away `postgres:18`, applies the scripts already in
 * `db/<name>`, compares the result with the tables in `com.circaso.<name>.data.tables` and writes the difference as
 * `V<n+1>__<what>.sql`. A draft can hold `DROP`s, so it is always read and edited before it is committed; only the
 * `Migrate` step ever applies a script.
 *
 * The drafting container starts without the module's schema. A `db/<name>/beforeMigrate.sql` that creates the schema
 * only when it is missing lets the existing scripts apply there; under `Migrate` the schema already exists, so the
 * same file does nothing.
 */
fun Project.draftMigrationsWithExposed(module: String) {
    apply(plugin = "org.jetbrains.exposed.plugin")
    val exposed = extensions.getByType<ExposedExtension>() as ExtensionAware
    exposed.extensions.configure<MigrationsExtension> {
        tablesPackage.set("com.circaso.$module.data.tables")
        fileDirectory.set(layout.projectDirectory.dir("src/main/resources/db/$module"))
        testContainersImageName.set("postgres:18")
        fileVersionFormat.set(VersionFormat.MAJOR_ONLY)
        useUpperCaseDescription.set(false)
    }
}
