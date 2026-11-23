pluginManagement {
    repositories {
        mavenCentral()
        if (providers.gradleProperty("codexGoogleCdn").orNull == "true") maven("https://redirector.gvt1.com/edgedl/android/maven2") else google()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        if (providers.gradleProperty("codexGoogleCdn").orNull == "true") maven("https://redirector.gvt1.com/edgedl/android/maven2") else google()
    }
}
rootProject.name = "CodexRemote"
include(":app")
include(":benchmark")
