import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.architectury.plugin.ArchitectPluginExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    java
    `maven-publish`
}

val minecraftVersion = "1.20.1"
val commonProjectPath = requireNotNull(stonecutter.node.sibling("common")).hierarchy.toString()
val commonProject = project(commonProjectPath)
evaluationDependsOn(commonProjectPath)

apply(plugin = "dev.architectury.loom")
apply(plugin = "architectury-plugin")
apply(plugin = "com.gradleup.shadow")

group = rootProject.property("maven_group") as String
version = rootProject.property("mod_version") as String

extensions.configure<BasePluginExtension>("base") {
    archivesName.set("Quick Skin - Forge - $minecraftVersion")
}

extensions.configure<ArchitectPluginExtension>("architectury") {
    minecraft = minecraftVersion
    platformSetupLoomIde()
    forge()
}

repositories {
    mavenCentral()
}

sourceSets {
    main {
        java.setSrcDirs(listOf(rootProject.file("forge/src/main/java")))
        resources.setSrcDirs(listOf(rootProject.file("forge/src/main/resources")))
    }
}

extensions.configure<LoomGradleExtensionAPI>("loom") {
    forge {
        mixinConfig("quickskin.mixins.json")
        mixinConfig("quickskin-ears.mixins.json")
    }
}

configurations {
    create("common") {
        isCanBeResolved = true
        isCanBeConsumed = false
    }
    create("shadowBundle") {
        isCanBeResolved = true
        isCanBeConsumed = false
    }
    compileClasspath.get().extendsFrom(configurations["common"])
    runtimeClasspath.get().extendsFrom(configurations["common"])
    named("developmentForge") {
        extendsFrom(configurations["common"])
    }
}

dependencies {
    "minecraft"("net.minecraft:minecraft:${rootProject.property("minecraft_version_1_20_1")}")
    "mappings"(project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())
    "forge"("net.minecraftforge:forge:${rootProject.property("forge_version_1_20_1")}")
    "modImplementation"(
        "dev.architectury:architectury-forge:${rootProject.property("architectury_api_version_1_20_1")}"
    )

    "common"(project.files(commonProject.tasks.named("jar")))
    "shadowBundle"(project.files(commonProject.tasks.named("transformProductionForge")))
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

tasks.withType<Jar>().configureEach {
    manifest.attributes.keys.filter { it.startsWith("Stonecutter-") }.forEach {
        manifest.attributes.remove(it)
    }
    doFirst {
        manifest.attributes.keys.filter { it.startsWith("Stonecutter-") }.forEach {
            manifest.attributes.remove(it)
        }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("META-INF/mods.toml") {
        expand(mapOf("version" to inputs.properties["version"]))
    }
}

tasks.named<ShadowJar>("shadowJar") {
    configurations = listOf(project.configurations["shadowBundle"])
    archiveClassifier.set("dev-shadow")
}

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    dependsOn(tasks.named("shadowJar"))
    val shadowJar = tasks.named<ShadowJar>("shadowJar")
    inputFile.set(shadowJar.get().archiveFile)
}
