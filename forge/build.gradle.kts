import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.architectury.plugin.ArchitectPluginExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    java
    `maven-publish`
}

apply(from = rootProject.file("gradle/archive-conventions.gradle.kts"))

val minecraftVersion = stonecutter.current.version
val commonProjectPath = requireNotNull(stonecutter.node.sibling("common")).hierarchy.toString()
val commonProject = project(commonProjectPath)
evaluationDependsOn(commonProjectPath)

apply(plugin = "dev.architectury.loom")
apply(plugin = "architectury-plugin")
apply(plugin = "com.gradleup.shadow")

fun Project.versionProp(base: String): String =
    rootProject.property("${base}_${minecraftVersion.replace(".", "_")}") as String

group = rootProject.property("maven_group") as String
version = rootProject.property("mod_version") as String
extensions.extraProperties["quickSkinFmlMinecraftVersion"] = minecraftVersion
apply(from = rootProject.file("gradle/fml-metadata-conventions.gradle.kts"))

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

// Build the automation as a physically separate mod. Main output is compile-only input here and is
// never copied into the harness JAR installed by packaged-runtime E2E.
val mainSourceSet = sourceSets.named("main").get()
val e2eSourceSet = sourceSets.create("e2e") {
    java.setSrcDirs(
        listOf(
            rootProject.file("forge/src/e2e/java"),
            rootProject.file("common/src/e2e/java"),
        )
    )
    resources.setSrcDirs(
        listOf(
            rootProject.file("forge/src/e2e/resources"),
            rootProject.file("common/src/e2e/resources"),
        )
    )
    compileClasspath += mainSourceSet.output + mainSourceSet.compileClasspath
    runtimeClasspath += output + compileClasspath
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
    "minecraft"("net.minecraft:minecraft:${versionProp("minecraft_version")}")
    "mappings"(project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())
    "forge"("net.minecraftforge:forge:${versionProp("forge_version")}")
    "modImplementation"(
        "dev.architectury:architectury-forge:${versionProp("architectury_api_version")}"
    )

    "common"(project.files(commonProject.tasks.named("jar")))
    "shadowBundle"(project.files(commonProject.tasks.named("transformProductionForge")))
    "shadowBundle"("org.sejda.imageio:webp-imageio:0.1.6")
}

val javaVersion = versionProp("java_version").toInt()
java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(javaVersion))
    sourceCompatibility = JavaVersion.toVersion(javaVersion)
    targetCompatibility = JavaVersion.toVersion(javaVersion)
    withSourcesJar()
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
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

extensions.extraProperties["quickSkinE2ESourceSet"] = e2eSourceSet
extensions.extraProperties["quickSkinE2ELoaderLabel"] = "Forge"
extensions.extraProperties["quickSkinE2ENoRemap"] = false
extensions.extraProperties["quickSkinE2EMinecraftVersion"] = minecraftVersion
apply(from = rootProject.file("gradle/e2e-harness-conventions.gradle.kts"))
