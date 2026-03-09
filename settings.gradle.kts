pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://files.minecraftforge.net/maven/")
        maven("https://maven.neoforged.net/releases")
        mavenCentral()
        gradlePluginPortal()
    }
}

rootProject.name = "quick-skin"

// Read version from gradle.properties
val minecraftVersion = providers.gradleProperty("minecraft_version").get()

// Always include common and fabric
include("common")
include("fabric")

// Conditionally include platform-specific modules
when (minecraftVersion) {
    "1.20.1" -> include("forge")
    "1.21.1", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10" -> include("neoforge")
    else -> throw GradleException("Unknown minecraft_version: $minecraftVersion")
}
