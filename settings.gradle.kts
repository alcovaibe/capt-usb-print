pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "CAPT USB Print"
include(":app")
// A separate UID is essential for reproducible URI-grant tests; never part of the app APK.
if (providers.gradleProperty("officeIntakeFixture").orNull == "true") include(":office-uri-fixture")
