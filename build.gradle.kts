import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    id("dev.architectury.loom") version "1.11-SNAPSHOT" apply false
    id("architectury-plugin") version "3.4-SNAPSHOT"
    id("com.gradleup.shadow") version "8.3.6" apply false
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
    apply(plugin = "dev.architectury.loom")
    apply(plugin = "architectury-plugin")
    apply(plugin = "maven-publish")

    val minecraftVersion = project.findProperty("minecraft_version") as String
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
        "mappings"(project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())
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
