import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.architectury.plugin.ArchitectPluginExtension
import groovy.json.JsonSlurper
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

val releaseMatrixFile = rootProject.file("release/release-matrix.json")
check(releaseMatrixFile.isFile) { "Missing central release matrix: $releaseMatrixFile" }
val releaseMatrix = JsonSlurper().parse(releaseMatrixFile) as Map<*, *>
val releaseProject = releaseMatrix["project"] as Map<*, *>
val releaseArtifact = (releaseMatrix["artifacts"] as List<*>)
    .map { it as Map<*, *> }
    .single { it["artifact_node"] == "forge-$minecraftVersion" }
val releaseMetadata = releaseArtifact["metadata"] as Map<*, *>

fun matrixString(values: Map<*, *>, key: String): String =
    requireNotNull(values[key]) { "Missing '$key' in $releaseMatrixFile" }.toString()

fun normalizeForgeToml(file: File) {
    fun quoted(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")

    var activeDependency: String? = null
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
            trimmed.startsWith("license = ") -> {
                licenseCount++
                "license = \"${quoted(matrixString(releaseProject, "license"))}\""
            }
            trimmed.startsWith("issueTrackerURL = ") -> {
                issueCount++
                "issueTrackerURL = \"${quoted(matrixString(releaseProject, "issues"))}\""
            }
            trimmed.startsWith("displayName = ") -> {
                displayNameCount++
                "displayName = \"${quoted(matrixString(releaseProject, "name"))}\""
            }
            trimmed.startsWith("displayURL = ") -> {
                displayUrlCount++
                "displayURL = \"${quoted(matrixString(releaseProject, "homepage"))}\""
            }
            activeDependency == "minecraft" && trimmed.startsWith("versionRange = ") -> {
                minecraftRangeCount++
                "versionRange = \"${quoted(matrixString(releaseMetadata, "minecraft"))}\""
            }
            activeDependency == "architectury" && trimmed.startsWith("versionRange = ") -> {
                architecturyRangeCount++
                "versionRange = \"${quoted(matrixString(releaseMetadata, "architectury"))}\""
            }
            else -> line
        }
    } + "\n"

    val descriptionPattern = Regex("(?s)description\\s*=\\s*'''.*?'''")
    val descriptionCount = descriptionPattern.findAll(text).count()
    text = descriptionPattern.replace(
        text,
        "description = '''\n${matrixString(releaseProject, "description")}\n'''",
    )
    check(
        licenseCount == 1 && issueCount == 1 && displayNameCount == 1 && displayUrlCount == 1 &&
            minecraftRangeCount == 1 && architecturyRangeCount == 1 && descriptionCount == 1
    ) {
        "Unexpected Forge metadata shape in $file (license=$licenseCount, issues=$issueCount, " +
            "name=$displayNameCount, URL=$displayUrlCount, minecraft=$minecraftRangeCount, " +
            "architectury=$architecturyRangeCount, description=$descriptionCount)"
    }
    file.writeText(text, Charsets.UTF_8)
}

apply(plugin = "dev.architectury.loom")
apply(plugin = "architectury-plugin")
apply(plugin = "com.gradleup.shadow")

fun Project.versionProp(base: String): String =
    rootProject.property("${base}_${minecraftVersion.replace(".", "_")}") as String

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

tasks.processResources {
    inputs.property("version", project.version)
    inputs.file(releaseMatrixFile)
    filesMatching("META-INF/mods.toml") {
        expand(mapOf("version" to inputs.properties["version"]))
    }
    doLast {
        val metadataFile = destinationDir.resolve("META-INF/mods.toml")
        check(metadataFile.isFile) { "Processed Forge metadata is missing: $metadataFile" }
        normalizeForgeToml(metadataFile)
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

val e2eArchiveBaseName = "Quick Skin E2E - Forge - $minecraftVersion"
val e2eHarnessDevJar = tasks.register<Jar>("e2eHarnessDevJar") {
    group = "verification"
    description = "Packages the named intermediary input for the remapped E2E harness."
    dependsOn(tasks.named(e2eSourceSet.classesTaskName))
    from(e2eSourceSet.output)
    archiveBaseName.set(e2eArchiveBaseName)
    archiveVersion.set("0.0.0")
    archiveClassifier.set("dev")
    destinationDirectory.set(layout.buildDirectory.dir("devlibs"))
}
val productionRemap = tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar")
tasks.register<net.fabricmc.loom.task.RemapJarTask>("remapE2EHarnessJar") {
    group = "verification"
    description = "Remaps the separate E2E harness for a real Forge production runtime."
    dependsOn(e2eHarnessDevJar)
    inputFile.set(e2eHarnessDevJar.flatMap { it.archiveFile })
    sourceNamespace.set(productionRemap.flatMap { it.sourceNamespace })
    targetNamespace.set(productionRemap.flatMap { it.targetNamespace })
    classpath.from(productionRemap.map { it.classpath }, e2eSourceSet.compileClasspath)
    addNestedDependencies.set(false)
    readMixinConfigsFromManifest.set(false)
    injectAccessWidener.set(false)
    useMixinAP.set(false)
    archiveBaseName.set(e2eArchiveBaseName)
    archiveVersion.set("0.0.0")
    archiveClassifier.set("")
}
