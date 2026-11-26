import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

// A separate instrumentation APK exercises the unchanged, minified production APK.
// No implementation dependency on app classes and no application keep rules.
// https://developer.android.com/studio/test/advanced-test-setup#use-separate-test-modules
android {
    namespace = "com.blackmarlin.downloader.releasecheck"
    compileSdk = 36
    targetProjectPath = ":app"
    defaultConfig {
        minSdk = 36
        targetSdk = 36
        testApplicationId = "com.blackmarlin.downloader.releasecheck"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        create("release") {
            isDebuggable = false
            // AGP requires matching test shrinking when the tested APK has a mapping file.
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
