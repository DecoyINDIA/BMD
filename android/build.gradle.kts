import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.attributes.java.TargetJvmEnvironment

plugins {
    id("com.android.application") version "8.13.2" apply false
    id("com.android.test") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("org.jetbrains.kotlin.kapt") version "2.2.21" apply false
}

// AGP 8.13.2 resolves this exact lint tool in a detached configuration, so it is
// not present in the app's named configurations until lint tasks are realized.
val androidLintPreflight by configurations.creating {
    isCanBeResolved = true
    isCanBeConsumed = false
    attributes {
        attribute(Usage.USAGE_ATTRIBUTE, objects.named(Usage::class.java, Usage.JAVA_RUNTIME))
        attribute(Category.CATEGORY_ATTRIBUTE, objects.named(Category::class.java, Category.LIBRARY))
        attribute(LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE, objects.named(LibraryElements::class.java, LibraryElements.JAR))
        attribute(TargetJvmEnvironment.TARGET_JVM_ENVIRONMENT_ATTRIBUTE,
            objects.named(TargetJvmEnvironment::class.java, TargetJvmEnvironment.STANDARD_JVM))
    }
}
dependencies {
    androidLintPreflight("com.android.tools.lint:lint-gradle:31.13.2")
}

// Dependency transport only: do not compile, lint, test, package or consume local
// project outputs. Actual product gates run separately and are never retried.
tasks.register("resolveAndroidDependencies") {
    group = "verification"
    description = "Resolve external Android build/test dependencies before one-shot product gates."
    doLast {
        val lintArtifacts = androidLintPreflight.incoming.artifactView {
            componentFilter { it is ModuleComponentIdentifier }
        }.files.files
        logger.lifecycle("Resolved AGP lint tool: ${lintArtifacts.size} external artifacts")
        val selected = listOf(project(":app"), project(":release-check"))
        for (target in selected) {
            val configurations = target.configurations.filter { configuration ->
                val name = configuration.name
                configuration.isCanBeResolved && (
                    name.endsWith("CompileClasspath") || name.endsWith("RuntimeClasspath") ||
                    name.endsWith("AnnotationProcessor") || name == "robolectricSdk" ||
                    name.startsWith("lintClassPath") || name.startsWith("kotlinCompiler") ||
                    name.startsWith("kapt")
                )
            }
            check(configurations.isNotEmpty()) { "No dependency configurations selected for ${target.path}" }
            for (configuration in configurations) {
                val external = configuration.incoming.artifactView {
                    componentFilter { it is ModuleComponentIdentifier }
                }.files.files
                logger.lifecycle("Resolved ${target.path}:${configuration.name}: ${external.size} external artifacts")
            }
        }
    }
}
