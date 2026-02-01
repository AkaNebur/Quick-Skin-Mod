plugins {
    id("com.gradleup.shadow")
}

val minecraftVersion = project.findProperty("minecraft_version") as String
require(minecraftVersion == "1.21.1") { "NeoForge requires minecraft_version = 1.21.1" }

fun Project.prop(name: String) = project.property("${name}_1_21_1") as String

architectury {
    platformSetupLoomIde()
    neoForge()
}

sourceSets {
    main {
        java.srcDir("src/v1_21_1/java")
        resources.srcDir("src/v1_21_1/resources")
    }
}

repositories {
    maven("https://maven.neoforged.net/releases")
}

configurations {
    create("common")
    create("shadowBundle")
    compileClasspath.get().extendsFrom(configurations["common"])
    runtimeClasspath.get().extendsFrom(configurations["common"])
    getByName("developmentNeoForge").extendsFrom(configurations["common"])
}

dependencies {
    "neoForge"("net.neoforged:neoforge:${project.prop("neoforge_version")}")
    modImplementation("dev.architectury:architectury-neoforge:${project.prop("architectury_api_version")}")

    "common"(project(path = ":common", configuration = "namedElements")) { isTransitive = false }
    "shadowBundle"(project(path = ":common", configuration = "transformProductionNeoForge"))
    "shadowBundle"("org.sejda.imageio:webp-imageio:0.1.6")
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to project.version)
    }
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    configurations = listOf(project.configurations["shadowBundle"])
    archiveClassifier.set("dev-shadow")
}

tasks.named("remapJar") {
    dependsOn("shadowJar")
    val shadowJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
    mustRunAfter(shadowJar)
}
