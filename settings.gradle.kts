// Root settings for the standalone ClickTrust Android SDK build.
//
// Two repositories are listed for both the plugin classpath and runtime
// dependencies — Google's Maven for the Android Gradle Plugin and Maven
// Central for everything else. We deliberately keep the dependency graph
// here as small as possible (no OkHttp, no Retrofit, no Gson) so apps
// integrating the SDK don't inherit a runtime they didn't ask for.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Reject project-level repository declarations so we can audit the
    // full dependency graph from one place.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "clicktrust-android-sdk"
include(":clicktrust-sdk")
