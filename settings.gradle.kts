import groovy.json.JsonSlurper

pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.architectury.dev/")
        maven("https://files.minecraftforge.net/maven/")
        maven("https://maven.neoforged.net/releases")
        maven("https://maven.kikugie.dev/releases")
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("dev.kikugie.stonecutter") version "0.9.6"
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

val releaseMatrixFile = file("release/release-matrix.json")
check(releaseMatrixFile.isFile) { "Missing central release matrix: $releaseMatrixFile" }
val releaseMatrix = JsonSlurper().parse(releaseMatrixFile) as Map<*, *>
val releaseArtifacts = (releaseMatrix["artifacts"] as? List<*>)
    ?.map { artifact -> artifact as? Map<*, *> ?: error("Invalid artifact row in $releaseMatrixFile") }
    ?: error("Missing artifact inventory in $releaseMatrixFile")
val expectedLaneCount = (releaseMatrix["lane_count"] as? Number)?.toInt()
    ?: error("Missing lane_count in $releaseMatrixFile")
check(releaseArtifacts.size == expectedLaneCount) {
    "Release artifact inventory has ${releaseArtifacts.size} rows; expected lane_count=$expectedLaneCount"
}
val releaseLanes = releaseArtifacts.map { artifact ->
    val loader = artifact["loader"]?.toString() ?: error("Release artifact is missing loader")
    val version = artifact["artifact_version"]?.toString()
        ?: error("Release artifact is missing artifact_version")
    val node = artifact["artifact_node"]?.toString()
        ?: error("Release artifact is missing artifact_node")
    check(loader in setOf("fabric", "forge", "neoforge")) { "Unsupported release loader: $loader" }
    check(node == "$loader-$version") { "Release node $node does not match $loader $version" }
    loader to version
}
check(releaseLanes.distinct().size == releaseLanes.size) { "Duplicate lane in $releaseMatrixFile" }
check(releaseLanes.map { it.first }.toSet() == setOf("fabric", "forge", "neoforge")) {
    "Release lanes must cover Fabric, Forge, and NeoForge"
}
val releaseVersions = releaseLanes.map { it.second }.distinct().toTypedArray()

stonecutter {
    kotlinController = true
    centralScript = "build.gradle.kts"

    create(rootProject) {
        versions(*releaseVersions)

        branch("common") {
            versions(*releaseVersions)
        }
        listOf("fabric", "forge", "neoforge").forEach { loader ->
            branch(loader) {
                versions(
                    *releaseLanes
                        .filter { it.first == loader }
                        .map { it.second }
                        .toTypedArray()
                )
            }
        }
    }
}

rootProject.name = "quick-skin"
