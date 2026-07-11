pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://files.minecraftforge.net/maven/")
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.kikugie.dev/releases")
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.6"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

stonecutter {
    kotlinController = true
    centralScript = "build.gradle.kts"

    create(rootProject) {
        val versions = arrayOf("1.20.1", "1.21.1", "1.21.11", "26.1", "26.2")
        versions(*versions)

        branch("common") {
            versions(*versions)
        }
        branch("fabric") {
            versions(*versions)
        }
        branch("forge") {
            version("1.20.1")
        }
        branch("neoforge") {
            versions("1.21.1", "1.21.11", "26.1", "26.2")
        }
    }
}

rootProject.name = "quick-skin"
