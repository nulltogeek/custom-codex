pluginManagement {
    repositories {
        if (providers.gradleProperty("codexGoogleCdn").orNull == "true") maven("https://redirector.gvt1.com/edgedl/android/maven2") else google()
        mavenCentral(); gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (providers.gradleProperty("codexGoogleCdn").orNull == "true") maven("https://redirector.gvt1.com/edgedl/android/maven2") else google()
        mavenCentral()
    }
}
rootProject.name = "CodexRemote"
include(":app")
include(":benchmark")
