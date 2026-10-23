import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Packages & Environment: add, update and remove apk packages and CLI tool bundles, and look after the Linux environment.
// Compiled against the checked-in host API jar (api/pocketshell-api.jar); the host provides those
// classes at runtime through parent classloader delegation, so only this plugin's own classes are dexed.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "app.pocketshell.widget.packages"
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

// The HOST API jar is checked into this repo (api/pocketshell-api.jar,
// see api/PROVENANCE.md) so the build is standalone — no PocketShell
// checkout needed. At runtime the host provides these classes through
// parent classloader delegation.
dependencies {
    compileOnly(files(rootProject.file("api/pocketshell-api.jar")))
    compileOnly(platform(libs.compose.bom))
    compileOnly("org.jetbrains:annotations:23.0.0")
    runtimeOnly("org.jetbrains:annotations:23.0.0")
    compileOnly(libs.compose.ui)
    compileOnly(libs.compose.material3)
    compileOnly(libs.androidx.lifecycle.runtime.compose)
    // BackHandler rides androidx.activity.compose (what the host ships).
    compileOnly(libs.androidx.activity.compose)
    // The task-manager shell's nav + action icons ride the extended set
    // (what the host app itself ships, and the git plugin already uses).
    compileOnly(libs.compose.material.icons.extended)
    // The tick loop's Dispatchers / withContext / coroutineScope+async.
    compileOnly(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    // The Compose compiler plugin also compiles the test source set —
    // it refuses without a runtime on the classpath.
    testImplementation(platform(libs.compose.bom))
    testImplementation("androidx.compose.runtime:runtime")
    testImplementation(files(rootProject.file("api/pocketshell-api.jar")))
    // The probe gate tests exercise the HOST's ServerProbe, whose types
    // ride kotlinx.coroutines on the compile classpath of the jar itself.
    testImplementation(libs.kotlinx.coroutines.core)
}
