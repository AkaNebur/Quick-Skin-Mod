plugins {
    id("com.gradleup.shadow")
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

    if (minecraftVersion == "1.21.1") {
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
