// ps-widget-repo root build configuration.
// All versions pinned via gradle/libs.versions.toml — the same versions the
// PocketShell app builds with, so plugins stay binary-compatible with the
// host's runtime classpath (parent classloader delegation).
plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
