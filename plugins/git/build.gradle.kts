import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// M8.5 — the GIT WIDGET as a compiled PLUGIN: its own classes (probe,
// parser, presentation, app) are dexed and published to ps-widget-repo;
// everything else (Home Application contract, Compose, runtime launcher)
// is provided by the host at runtime through parent classloader
// delegation. See plugins/hello for the minimal plugin shape.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.pocketshell.widget.git"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The HOST API jar is checked into this repo (api/pocketshell-0.14.0-api.jar,
// see api/PROVENANCE.md) so the build is standalone — no PocketShell checkout
// needed. At runtime the host provides these classes through parent
// classloader delegation.
dependencies {
    compileOnly(files(rootProject.file("api/pocketshell-0.14.0-api.jar")))
    compileOnly(platform(libs.compose.bom))
    compileOnly("org.jetbrains:annotations:23.0.0")
    runtimeOnly("org.jetbrains:annotations:23.0.0")
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.material3)
    compileOnly(libs.androidx.lifecycle.runtime.compose)
    // GitApp's imports beyond hello's surface — host-provided at runtime:
    // BackHandler rides androidx.activity.compose; the refresh icon rides
    // the extended icon set (what the host app itself ships).
    compileOnly(libs.androidx.activity.compose)
    compileOnly(libs.compose.material.icons.extended)
    // GitApp's LaunchedEffect collect (Dispatchers / flow ops / withContext).
    compileOnly(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.runtime:runtime")
    // The probe tests use the HOST's ExecResult (this module's own git
    // classes come from its main source set, not the jar).
    testImplementation(files(rootProject.file("api/pocketshell-0.14.0-api.jar")))
    // Same coroutines surface the tests had as app-module tests: the main
    // classes under test reference kotlinx.coroutines types.
    testImplementation(libs.kotlinx.coroutines.core)
}
