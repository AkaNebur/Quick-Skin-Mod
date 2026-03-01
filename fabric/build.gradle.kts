plugins {
    id("com.gradleup.shadow")
    id("com.modrinth.minotaur")
    id("net.darkhax.curseforgegradle")
}

fun Project.versionProp(base: String): String {
    val minecraftVersion = project.findProperty("minecraft_version") as String
    return project.property("${base}_${minecraftVersion.replace(".", "_")}") as String
}

architectury {
    platformSetupLoomIde()
    fabric()
}

val minecraftVersion = project.findProperty("minecraft_version") as String
val versionDir = "v${minecraftVersion.replace(".", "_")}"

sourceSets {
    main {
        java.srcDir("src/$versionDir/java")
        resources.srcDir("src/$versionDir/resources")
    }
}

// Ensure src/main/java is still included (entry points live there)
// and version-specific java overrides (e.g. PlatformHelperImpl) come from src/$versionDir/java

configurations {
    create("common")
    create("shadowBundle")
    compileClasspath.get().extendsFrom(configurations["common"])
    runtimeClasspath.get().extendsFrom(configurations["common"])
    getByName("developmentFabric").extendsFrom(configurations["common"])
}

dependencies {
    modImplementation("net.fabricmc:fabric-loader:${project.versionProp("fabric_loader_version")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${project.versionProp("fabric_api_version")}")
    modImplementation("dev.architectury:architectury-fabric:${project.versionProp("architectury_api_version")}")

    "common"(project(path = ":common", configuration = "namedElements")) { isTransitive = false }
    "shadowBundle"(project(path = ":common", configuration = "transformProductionFabric"))

    if (minecraftVersion != "1.20.1") {
        "shadowBundle"("org.sejda.imageio:webp-imageio:0.1.6")
    }
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
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

val mcVersion = minecraftVersion
val supportedGameVersions = listOf(mcVersion)

val modLoaders = listOf("fabric")

val changelogFile = rootProject.file(rootProject.property("changelog_file") as String)
val changelogText = if (changelogFile.exists()) changelogFile.readText() else "No changelog provided"

val modrinthToken: String? = findProperty("modrinth_token") as String? ?: System.getenv("MODRINTH_TOKEN")
val curseforgeToken: String? = findProperty("curseforge_token") as String? ?: System.getenv("CURSEFORGE_TOKEN")

modrinth {
    token.set(modrinthToken ?: "")
    projectId.set(rootProject.property("modrinth_id") as String)
    versionNumber.set("${project.version}")
    versionName.set("Quick Skin ${project.version} [Fabric] [MC $mcVersion]")
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
