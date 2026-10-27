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
        // Vosk (offline speech recognition) is also mirrored here.
        maven {
            url = uri("https://alphacephei.com/maven/")
            content { includeGroup("com.alphacephei") }
        }
    }
}

rootProject.name = "VantaStudio"
include(":app")
