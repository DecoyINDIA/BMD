import org.gradle.api.GradleException
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.gradle.api.tasks.testing.Test

val nativeOutput = layout.buildDirectory.dir("generated/native")
val nativeRevision = providers.exec {
    workingDir(rootProject.projectDir.parentFile)
    commandLine("python3", "tools/android/source_revision.py")
}.standardOutput.asText.map(String::trim)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.blackmarlin.downloader"
    compileSdk = 36
    ndkVersion = "29.0.14206865"
    defaultConfig {
        applicationId = "com.blackmarlin.downloader"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "ENGINE_REVISION", "\"${nativeRevision.get()}\"")
        ndk { abiFilters += setOf("arm64-v8a", "x86_64") }
    }
    signingConfigs {
        val keystore = providers.environmentVariable("BMD_ANDROID_KEYSTORE").orNull?.takeIf { it.isNotBlank() }
        val storePasswordEnv = providers.environmentVariable("BMD_ANDROID_STORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
        val keyAliasEnv = providers.environmentVariable("BMD_ANDROID_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
        val keyPasswordEnv = providers.environmentVariable("BMD_ANDROID_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
        if (listOf(keystore, storePasswordEnv, keyAliasEnv, keyPasswordEnv).any { it != null }) {
            val missing = buildList {
                if (keystore == null) add("BMD_ANDROID_KEYSTORE")
                if (storePasswordEnv == null) add("BMD_ANDROID_STORE_PASSWORD")
                if (keyAliasEnv == null) add("BMD_ANDROID_KEY_ALIAS")
                if (keyPasswordEnv == null) add("BMD_ANDROID_KEY_PASSWORD")
            }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Incomplete Android signing configuration: set all of BMD_ANDROID_KEYSTORE, " +
                        "BMD_ANDROID_STORE_PASSWORD, BMD_ANDROID_KEY_ALIAS, and BMD_ANDROID_KEY_PASSWORD " +
                        "(missing: ${missing.joinToString(", ")})."
                )
            }
            create("production") {
                storeFile = file(keystore!!)
                storePassword = storePasswordEnv
                keyAlias = keyAliasEnv
                keyPassword = keyPasswordEnv
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("production")
        }
    }
    sourceSets.getByName("main") {
        jniLibs.srcDir(nativeOutput.map { it.dir("jniLibs") })
        assets.srcDir(nativeOutput.map { it.dir("assets") })
        assets.srcDir(layout.buildDirectory.dir("generated/androidNotices"))
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging {
        jniLibs {
            useLegacyPackaging = false
            // The native build performs deterministic stripping before hashing.
            // A second AGP strip would make packaged bytes diverge from provenance.
            keepDebugSymbols += "**/libbmd_android_bridge.so"
        }
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
    lint { abortOnError = true; checkReleaseBuilds = true }
    testOptions { unitTests.isIncludeAndroidResources = true; animationsDisabled = true }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
kapt { arguments { arg("room.schemaLocation", "$projectDir/schemas"); arg("room.incremental", "true") } }

// PublicationManagerTest pins API 34 and TransferCoordinatorTest API 33 (Android 13's
// foreground-service rules, AND-01). Every SDK a test pins must be staged here: offline,
// Robolectric fails a missing one in LocalDependencyResolver. Resolve them through Gradle's cache
// before testing instead of Robolectric's separate, uncached Maven downloader.
// One configuration per SDK: versions of the same module in one configuration are a conflict
// Gradle resolves by keeping only the newest, which silently dropped SDK 33's runtime.
val robolectricRuntimes = mapOf(
    33 to "org.robolectric:android-all-instrumented:13-robolectric-9030017-i7",
    34 to "org.robolectric:android-all-instrumented:14-robolectric-10818077-i7",
)
val robolectricSdks = robolectricRuntimes.map { (sdk, coordinate) ->
    configurations.create("robolectricSdk$sdk") {
        isCanBeConsumed = false
        isCanBeResolved = true
        isTransitive = false
    }.also { dependencies.add(it.name, coordinate) }
}
val robolectricSdkDirectory = layout.buildDirectory.dir("robolectric-sdk")
val prepareRobolectricSdk by tasks.registering(Sync::class) {
    group = "verification"
    description = "Stage the pinned Android runtimes for offline Robolectric tests."
    from(robolectricSdks)
    into(robolectricSdkDirectory)
}
tasks.withType<Test>().configureEach {
    dependsOn(prepareRobolectricSdk)
    inputs.dir(robolectricSdkDirectory)
        .withPropertyName("robolectricSdk")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("robolectric.offline", "true")
    systemProperty("robolectric.dependency.dir", robolectricSdkDirectory.get().asFile.absolutePath)
}

val buildNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Build the real Rust engine for arm64-v8a and x86_64 with locked dependencies."
    workingDir(rootProject.projectDir.parentFile)
    commandLine("python3", "tools/android/build_native.py")
    environment("CARGO_INCREMENTAL", "0")
    inputs.files(fileTree("../../crates/engine") { include("**/*.rs", "**/*.toml") },
        fileTree("../../crates/android-bridge") { include("**/*.rs", "**/*.toml") },
        fileTree("../../tools/android") { include("**/*.py") }, file("../../Cargo.toml"), file("../../Cargo.lock"), file("../../.cargo/config.toml"))
    inputs.files(fileTree("../third_party/licenses"), file("../../LICENSE"))
    inputs.property("sourceRevision", nativeRevision)
    inputs.property("ndkVersion", "29.0.14206865")
    inputs.property("rustVersion", "1.98.0")
    outputs.dir(nativeOutput)
    onlyIf { !providers.gradleProperty("skipNativeBuild").map(String::toBoolean).getOrElse(false) }
}
val verifyNative by tasks.registering(Exec::class) {
    group = "verification"
    description = "Reject missing/stale JNI binaries, wrong ABIs, missing symbols and invalid 16 KiB ELF alignment."
    workingDir(rootProject.projectDir.parentFile)
    commandLine("python3", "tools/android/verify_native.py")
    dependsOn(buildNative)
    // This guard deliberately runs even with -PskipNativeBuild. That flag may skip a
    // redundant compilation; it can never package an absent or stale native engine.
}
tasks.named("preBuild").configure { dependsOn(buildNative) }
tasks.configureEach {
    if ((name.startsWith("merge") && (name.endsWith("NativeLibs") || name.endsWith("Assets"))) ||
        name.startsWith("package") || name.startsWith("bundle")) {
        dependsOn(verifyNative)
    }
    if (name.startsWith("merge") && name.endsWith("Assets")) dependsOn("generateAndroidNotices")
    // AGP's lint model reads asset source directories directly, before mergeAssets.
    // Declare both generators here as well so Gradle never consumes undeclared output.
    if (name.contains("lint", ignoreCase = true)) dependsOn(verifyNative, "generateAndroidNotices")
}

apply(from = "android-notices.gradle.kts")

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.9.4")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    implementation("androidx.work:work-runtime-ktx:2.10.5")
    implementation("androidx.documentfile:documentfile:1.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.rustls:rustls-platform-verifier:0.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.room:room-testing:2.8.5")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:rules:1.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
