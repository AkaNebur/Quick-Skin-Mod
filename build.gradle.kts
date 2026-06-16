import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    // Loom 1.17 + architectury-plugin 3.5.x + shadow 8.3.11 are required for the Gradle 9.4 / Java 25
    // toolchain that Minecraft 26.1.x needs. Loom is not Minecraft-version-gated, so 1.17 also builds
    // the existing 1.20.1-1.21.11 targets. shadow 8.3.11 backports Gradle 9 support while keeping the 8.x API.
    id("dev.architectury.loom") version "1.17.480" apply false
    // Minecraft 26.1+ is unobfuscated; Architectury Loom builds it through the non-remapping plugin
    // (same artifact, different plugin id). Both markers are on the classpath; one is applied per version.
    id("dev.architectury.loom-no-remap") version "1.17.480" apply false
    id("architectury-plugin") version "3.5.167"
    id("com.gradleup.shadow") version "8.3.11" apply false
    id("com.modrinth.minotaur") version "2.+" apply false
    id("net.darkhax.curseforgegradle") version "1.1.18" apply false
}

// Helper to get version-specific properties
fun Project.versionProp(base: String): String {
    val minecraftVersion = project.findProperty("minecraft_version") as String
    val propName = "${base}_${minecraftVersion.replace(".", "_")}"
    return project.property(propName) as String
}

architectury {
    minecraft = project.versionProp("minecraft_version")
}

allprojects {
    group = project.property("maven_group") as String
    version = project.property("mod_version") as String
}

subprojects {
    val minecraftVersion = project.findProperty("minecraft_version") as String
    val isNoRemap = minecraftVersion.startsWith("26.")

    // 26.1+ (unobfuscated) -> non-remapping Loom; everything else -> classic remapping Loom.
    apply(plugin = if (isNoRemap) "dev.architectury.loom-no-remap" else "dev.architectury.loom")
    apply(plugin = "architectury-plugin")
    apply(plugin = "maven-publish")

    val javaVersion = JavaVersion.toVersion(project.versionProp("java_version"))

    extensions.configure<BasePluginExtension>("base") {
        val platformName = when (project.name) {
            "fabric" -> "Fabric"
            "forge" -> "Forge"
            "neoforge" -> "NeoForge"
            else -> project.name
        }
        archivesName.set("Quick Skin - $platformName - $minecraftVersion")
    }

    repositories {
        mavenCentral()
    }

    extensions.configure<LoomGradleExtensionAPI>("loom") {
        // Loom configuration
    }

    dependencies {
        "minecraft"("net.minecraft:minecraft:${project.versionProp("minecraft_version")}")
        // Minecraft 26.1+ ships deobfuscated (official Mojang names baked in), so Mojang no longer
        // publishes a separate proguard mapping file and officialMojangMappings() fails to resolve.
        // Per the Fabric 26.1 porting guide, omit the mappings dependency for these versions.
        if (!minecraftVersion.startsWith("26.")) {
            "mappings"(project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())
        }
    }

    extensions.configure<JavaPluginExtension>("java") {
        withSourcesJar()
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }

    tasks.withType<JavaCompile> {
        options.release.set(javaVersion.majorVersion.toInt())
    }

    extensions.configure<PublishingExtension>("publishing") {
        publications {
            create<MavenPublication>("mavenJava") {
                artifactId = project.extensions.getByType<BasePluginExtension>().archivesName.get()
                from(components["java"])
            }
        }
    }
}
