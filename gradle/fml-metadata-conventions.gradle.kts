val matrixState = gradle.extensions.extraProperties
val releaseMatrixFile = matrixState["quickSkinReleaseMatrixFile"] as java.io.File
val releaseMatrix = matrixState["quickSkinReleaseMatrix"] as Map<*, *>
@Suppress("UNCHECKED_CAST")
val releaseArtifacts = matrixState["quickSkinReleaseArtifacts"] as List<Map<*, *>>
@Suppress("UNCHECKED_CAST")
val matrixString = matrixState["quickSkinMatrixString"]
    as java.util.function.BiFunction<Map<*, *>, String, String>

val minecraftVersion = extensions.extraProperties["quickSkinFmlMinecraftVersion"].toString()
val loader = project.path.split(':').getOrNull(1)
    ?: error("Cannot derive loader branch from $path")
check(loader == "forge" || loader == "neoforge") {
    "FML metadata conventions apply only to Forge/NeoForge projects, not $path"
}
val loaderLabel = if (loader == "forge") "Forge" else "NeoForge"
val releaseProject = releaseMatrix["project"] as Map<*, *>
val releaseArtifact = releaseArtifacts.single {
    it["artifact_node"] == "$loader-$minecraftVersion"
}
val releaseMetadata = releaseArtifact["metadata"] as Map<*, *>
val metadataPath = matrixString.apply(releaseMetadata, "file")

fun quoted(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

fun normalizePackMetadata(file: java.io.File) {
    var text = file.readText(Charsets.UTF_8)
    val packFormatPattern = Regex("(?m)^(\\s*\\\"pack_format\\\"\\s*:\\s*)\\d+(\\s*[,}]?)$")
    val serverDataPattern =
        Regex("(?m)^(\\s*\\\"forge:server_data_pack_format\\\"\\s*:\\s*)\\d+(\\s*[,}]?)$")
    check(packFormatPattern.findAll(text).count() == 1) {
        "Unexpected pack_format shape in $file"
    }
    check(serverDataPattern.findAll(text).count() == 1) {
        "Unexpected forge:server_data_pack_format shape in $file"
    }
    text = packFormatPattern.replace(text) { match ->
        match.groupValues[1] + releaseMetadata["pack_format"] + match.groupValues[2]
    }
    text = serverDataPattern.replace(text) { match ->
        match.groupValues[1] + releaseMetadata["server_data_pack_format"] + match.groupValues[2]
    }
    file.writeText(text, Charsets.UTF_8)
}

fun normalizeFmlToml(file: java.io.File) {
    var activeDependency: String? = null
    var loaderApiCount = 0
    var loaderRangeCount = 0
    var licenseCount = 0
    var issueCount = 0
    var displayNameCount = 0
    var displayUrlCount = 0
    var minecraftRangeCount = 0
    var architecturyRangeCount = 0
    var text = file.readLines(Charsets.UTF_8).joinToString("\n") { line ->
        val trimmed = line.trim()
        if (trimmed == "[[dependencies.quickskin]]") activeDependency = null
        if (trimmed.startsWith("modId = \"")) {
            activeDependency = trimmed.substringAfter('"').substringBefore('"')
        }
        when {
            trimmed.startsWith("loaderVersion = ") -> {
                loaderApiCount++
                "loaderVersion = \"${quoted(matrixString.apply(releaseMetadata, "loader_api"))}\""
            }
            trimmed.startsWith("license = ") -> {
                licenseCount++
                "license = \"${quoted(matrixString.apply(releaseProject, "license"))}\""
            }
            trimmed.startsWith("issueTrackerURL = ") -> {
                issueCount++
                "issueTrackerURL = \"${quoted(matrixString.apply(releaseProject, "issues"))}\""
            }
            trimmed.startsWith("displayName = ") -> {
                displayNameCount++
                "displayName = \"${quoted(matrixString.apply(releaseProject, "name"))}\""
            }
            trimmed.startsWith("displayURL = ") -> {
                displayUrlCount++
                "displayURL = \"${quoted(matrixString.apply(releaseProject, "homepage"))}\""
            }
            activeDependency == "minecraft" && trimmed.startsWith("versionRange = ") -> {
                minecraftRangeCount++
                "versionRange = \"${quoted(matrixString.apply(releaseArtifact, "metadata_range"))}\""
            }
            activeDependency == "architectury" && trimmed.startsWith("versionRange = ") -> {
                architecturyRangeCount++
                "versionRange = \"${quoted(matrixString.apply(releaseMetadata, "architectury"))}\""
            }
            activeDependency == loader && trimmed.startsWith("versionRange = ") -> {
                loaderRangeCount++
                "versionRange = \"${quoted(matrixString.apply(releaseMetadata, "loader"))}\""
            }
            else -> line
        }
    } + "\n"

    val descriptionPattern = Regex("(?s)description\\s*=\\s*'''.*?'''")
    val descriptionCount = descriptionPattern.findAll(text).count()
    text = descriptionPattern.replace(
        text,
        "description = '''\n${matrixString.apply(releaseProject, "description")}\n'''",
    )
    check(
        loaderApiCount == 1 && loaderRangeCount == 1 && licenseCount == 1 && issueCount == 1 &&
            displayNameCount == 1 && displayUrlCount == 1 && minecraftRangeCount == 1 &&
            architecturyRangeCount == 1 && descriptionCount == 1
    ) {
        "Unexpected $loaderLabel metadata shape in $file (loaderApi=$loaderApiCount, " +
            "loaderRange=$loaderRangeCount, license=$licenseCount, " +
            "issues=$issueCount, name=$displayNameCount, URL=$displayUrlCount, " +
            "minecraft=$minecraftRangeCount, architectury=$architecturyRangeCount, " +
            "description=$descriptionCount)"
    }
    file.writeText(text, Charsets.UTF_8)
}

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("processResources") {
    inputs.property("version", project.version)
    inputs.file(releaseMatrixFile)
    filesMatching(metadataPath) {
        expand("version" to project.version)
    }
    doLast {
        val metadataFile = destinationDir.resolve(metadataPath)
        check(metadataFile.isFile) { "Processed $loaderLabel metadata is missing: $metadataFile" }
        normalizeFmlToml(metadataFile)
        val packMetadataFile = destinationDir.resolve("pack.mcmeta")
        check(packMetadataFile.isFile) {
            "Processed $loaderLabel pack metadata is missing: $packMetadataFile"
        }
        normalizePackMetadata(packMetadataFile)
    }
}
