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

// Auto-provisions the per-version JDK (17 for 1.20.1, 21 for 1.21.x, 25 for 26.x) declared by the
// java.toolchain block in build.gradle.kts, so dev runs launch on the correct JVM without manual setup.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
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
    "1.21.1", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11",
    "26.1", "26.1.1", "26.1.2", "26.2" -> include("neoforge")
    else -> throw GradleException("Unknown minecraft_version: $minecraftVersion")
}
