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
        // The classic Xposed API lives here (jcenter is gone).
        maven("https://api.xposed.info/")
    }
}

rootProject.name = "reelsgone"
include(":app")
