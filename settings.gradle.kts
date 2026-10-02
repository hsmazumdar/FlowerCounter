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
        // OpenCV publishes official Android AARs to Maven Central since 4.9.0.
        mavenCentral()
    }
}

rootProject.name = "FlowerCounter"
include(":app")
