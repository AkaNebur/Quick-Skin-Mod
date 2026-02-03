plugins {
    id("com.gradleup.shadow")
    id("com.modrinth.minotaur")
    id("net.darkhax.curseforgegradle")
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

tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
    dependsOn("shadowJar")
    val shadowJar = tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar")
    mustRunAfter(shadowJar)
    inputFile.set(shadowJar.get().archiveFile)
}

// ===== PUBLISHING CONFIGURATION =====

val mcVersion = "1.21.1"
val supportedGameVersions = listOf(mcVersion)
val modLoaders = listOf("neoforge")

val changelogFile = rootProject.file(rootProject.property("changelog_file") as String)
val changelogText = if (changelogFile.exists()) changelogFile.readText() else "No changelog provided"

val modrinthToken: String? = findProperty("modrinth_token") as String? ?: System.getenv("MODRINTH_TOKEN")
val curseforgeToken: String? = findProperty("curseforge_token") as String? ?: System.getenv("CURSEFORGE_TOKEN")

modrinth {
    token.set(modrinthToken ?: "")
    projectId.set(rootProject.property("modrinth_id") as String)
    versionNumber.set("${project.version}")
    versionName.set("Quick Skin ${project.version} [NeoForge] [MC $mcVersion]")
    versionType.set("release")
    uploadFile.set(tasks.named("remapJar"))
    gameVersions.addAll(supportedGameVersions)
    loaders.addAll(modLoaders)
    changelog.set(changelogText)
}

tasks.register<net.darkhax.curseforgegradle.TaskPublishCurseForge>("publishCurseForge") {
    dependsOn(tasks.named("remapJar"))
    apiToken = curseforgeToken ?: ""
    val mainFile = upload(rootProject.property("curseforge_id") as String, tasks.named("remapJar").get().outputs.files.singleFile)
    mainFile.changelogType = "markdown"
    mainFile.changelog = changelogText
    mainFile.releaseType = "release"
    supportedGameVersions.forEach { mainFile.addGameVersion(it) }
    modLoaders.forEach { mainFile.addModLoader(it) }
    doFirst {
        if (curseforgeToken.isNullOrEmpty()) throw GradleException("curseforge_token not set!")
    }
}

tasks.register("publishAll") {
    group = "publishing"
    description = "Publishes to both Modrinth and CurseForge"
    dependsOn("modrinth", "publishCurseForge")
}
