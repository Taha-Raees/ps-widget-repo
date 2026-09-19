import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// M8.5 — the PILOT WIDGET PLUGIN ("hello"): real compiled code that the
// app downloads (plugin.dex), verifies (sha256 from the catalog) and
// loads with DexClassLoader. It compiles against the HOST's classes
// (api/pocketshell-api.jar, checked into this repo — see
// api/PROVENANCE.md) plus the SAME Compose stack — the host provides those
// classes at runtime through parent classloader delegation, so the plugin
// dex carries only this widget's own classes. This is the minimal plugin
// shape; git/sync are the same build with a bigger source tree.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "repo.hello"
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
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// The host's compiled classes: the plugin compiles against them and, at
// runtime, resolves them through parent classloader delegation.
dependencies {
    compileOnly(files(rootProject.file("api/pocketshell-api.jar")))
    compileOnly(platform(libs.compose.bom))
    compileOnly(libs.compose.ui) // api-brings runtime
    compileOnly(libs.compose.material3)
    // Consistent-resolution alignment: compile and runtime variants must
    // agree on the transitive annotations version across both classpaths.
    compileOnly("org.jetbrains:annotations:23.0.0")
    runtimeOnly("org.jetbrains:annotations:23.0.0")
    compileOnly(libs.androidx.lifecycle.runtime.compose)
}

// Publishing (jar -> d8 -> releases/<id>/<version>/plugin.jar) is NOT a
// Gradle task: it is tools/build-plugin.sh at the repo root, which jars
// build/tmp/kotlin-classes/debug and dexes it.
