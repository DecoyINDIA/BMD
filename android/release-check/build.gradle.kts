import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// This test-only application owns its entire runner runtime. AGP's com.android.test
// subtracts dependencies packaged by the target and reuses the target's R8 mapping;
// that is unsafe when the target has already removed runner-only library methods.
// The explicit instrumentation manifest targets the unchanged production APK.
// There is deliberately no project dependency, target mapping, or app keep rule.
android {
    namespace = "com.blackmarlin.downloader.releasecheck"
    compileSdk = 36
    // AndroidJUnitRunner retains compatibility with these platform test classes.
    // A standalone test-only APK must declare what com.android.test adds for it.
    // https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/test-setup
    useLibrary("android.test.base")
    useLibrary("android.test.runner")
    defaultConfig {
        applicationId = "com.blackmarlin.downloader.releasecheck"
        minSdk = 36
        targetSdk = 36
    }
    buildTypes {
        getByName("release") {
            isDebuggable = false
            // Only this disposable harness's classes use these R8 rules.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = null
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents {
    beforeVariants { it.enable = it.buildType == "release" }
}
kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }

dependencies {
    implementation("androidx.test:runner:1.6.2")
    implementation("androidx.test.ext:junit:1.2.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    // AndroidX Test references these annotations; provide them to the harness R8 run.
    compileOnly("com.google.errorprone:error_prone_annotations:2.36.0")
}
