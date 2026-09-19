// ps-widget-repo — standalone plugin development ecosystem.
// This project has NO dependency on the PocketShell app repository: the
// host API classes come from the checked-in api/pocketshell-<v>-api.jar.
pluginManagement {
    repositories {
        gradlePluginPortal()
        google()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "ps-widget-repo"

include(":plugins:git")
include(":plugins:sync")
include(":plugins:hello")
