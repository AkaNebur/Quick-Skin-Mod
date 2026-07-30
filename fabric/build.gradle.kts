import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.architectury.plugin.ArchitectPluginExtension
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.gradle.api.tasks.Sync

plugins {
    java
    `maven-publish`
}

apply(from = rootProject.file("gradle/archive-conventions.gradle.kts"))

val minecraftVersion = stonecutter.current.version
val generatedStonecutterJava = layout.buildDirectory.dir("generated/stonecutter/main/java")
val consolidatedJava = layout.buildDirectory.dir("generated/consolidated/main/java")
val commonProjectPath = requireNotNull(stonecutter.node.sibling("common")).hierarchy.toString()
val commonProject = project(commonProjectPath)
evaluationDependsOn(commonProjectPath)

val matrixState = gradle.extensions.extraProperties
val releaseMatrixFile = matrixState["quickSkinReleaseMatrixFile"] as java.io.File
val releaseMatrix = matrixState["quickSkinReleaseMatrix"] as Map<*, *>
@Suppress("UNCHECKED_CAST")
val releaseArtifacts = matrixState["quickSkinReleaseArtifacts"] as List<Map<*, *>>
@Suppress("UNCHECKED_CAST")
val matrixString = matrixState["quickSkinMatrixString"]
    as java.util.function.BiFunction<Map<*, *>, String, String>
val releaseProject = releaseMatrix["project"] as Map<*, *>
val releaseArtifact = releaseArtifacts
    .single { it["artifact_node"] == "fabric-$minecraftVersion" }
val isNoRemap = releaseArtifact["no_remap"] as? Boolean
    ?: error("Release artifact fabric-$minecraftVersion is missing boolean no_remap")
val releaseMetadata = releaseArtifact["metadata"] as Map<*, *>

apply(plugin = if (isNoRemap) "dev.architectury.loom-no-remap" else "dev.architectury.loom")
apply(plugin = "architectury-plugin")
apply(plugin = "com.gradleup.shadow")

fun Project.versionProp(base: String): String =
    rootProject.property("${base}_${minecraftVersion.replace(".", "_")}") as String

group = rootProject.property("maven_group") as String
version = rootProject.property("mod_version") as String

extensions.configure<BasePluginExtension>("base") {
    archivesName.set("Quick Skin - Fabric - $minecraftVersion")
}

extensions.configure<ArchitectPluginExtension>("architectury") {
    minecraft = minecraftVersion
    platformSetupLoomIde()
    fabric()
}

repositories {
    mavenCentral()
}

val prepareConsolidatedJava = tasks.register<Sync>("prepareConsolidatedJava") {
    dependsOn("stonecutterGenerate")
    from(generatedStonecutterJava)
    into(consolidatedJava)
}

sourceSets {
    main {
        java.setSrcDirs(listOf(consolidatedJava))
        resources.setSrcDirs(listOf(rootProject.file("fabric/src/main/resources")))
    }
}

tasks.named("compileJava") {
    dependsOn(prepareConsolidatedJava)
}
tasks.matching { it.name == "sourcesJar" }.configureEach {
    dependsOn(prepareConsolidatedJava)
}

// The harness compiles against the selected node's named main output, but its JAR contains only the
// separate test mod. Production runtime profiles install this harness beside the exact release JAR;
// they never put main output or a development Quick Skin JAR on the runtime classpath.
val mainSourceSet = sourceSets.named("main").get()
val e2eSourceSet = sourceSets.create("e2e") {
    java.setSrcDirs(
        listOf(
            rootProject.file("fabric/src/e2e/java"),
            rootProject.file("common/src/e2e/java"),
        )
    )
    resources.setSrcDirs(
        listOf(
            rootProject.file("fabric/src/e2e/resources"),
            rootProject.file("common/src/e2e/resources"),
        )
    )
    compileClasspath += mainSourceSet.output + mainSourceSet.compileClasspath
    runtimeClasspath += output + compileClasspath
}

extensions.configure<LoomGradleExtensionAPI>("loom") {
    if (isNoRemap) {
        val awFile = rootProject.file("fabric/src/main/resources/quick-skin.accesswidener")
        if (awFile.exists()) accessWidenerPath.set(awFile)
    }
}

configurations {
    create("common")
    create("shadowBundle")
    compileClasspath.get().extendsFrom(configurations["common"])
    runtimeClasspath.get().extendsFrom(configurations["common"])
    findByName("developmentFabric")?.extendsFrom(configurations["common"])
}

dependencies {
    "minecraft"("net.minecraft:minecraft:${versionProp("minecraft_version")}")
    if (!isNoRemap) {
        "mappings"(project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())
    }

    val modImpl = if (isNoRemap) "implementation" else "modImplementation"
    modImpl("net.fabricmc:fabric-loader:${versionProp("fabric_loader_version")}")
    modImpl("net.fabricmc.fabric-api:fabric-api:${versionProp("fabric_api_version")}")
    modImpl("dev.architectury:architectury-fabric:${versionProp("architectury_api_version")}")

    "common"(project.files(commonProject.tasks.named("jar")))
    "shadowBundle"(project.files(commonProject.tasks.named("transformProductionFabric")))

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
    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
    doLast {
        val metadataFile = destinationDir.resolve("fabric.mod.json")
        check(metadataFile.isFile) { "Processed Fabric metadata is missing: $metadataFile" }
        @Suppress("UNCHECKED_CAST")
        val json = JsonSlurper().parse(metadataFile) as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val contact = json["contact"] as MutableMap<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val depends = json["depends"] as MutableMap<String, Any?>

        json["name"] = matrixString.apply(releaseProject, "name")
        json["description"] = matrixString.apply(releaseProject, "description")
        json["license"] = matrixString.apply(releaseProject, "license")
        contact["homepage"] = matrixString.apply(releaseProject, "homepage")
        contact["sources"] = matrixString.apply(releaseProject, "sources")
        contact["issues"] = matrixString.apply(releaseProject, "issues")
        depends["fabricloader"] = matrixString.apply(releaseMetadata, "loader")
        depends["minecraft"] = matrixString.apply(releaseArtifact, "metadata_range")
        depends["architectury"] = matrixString.apply(releaseMetadata, "architectury")
        val suggestions = releaseMetadata["suggests"]
        if (suggestions is Map<*, *>) {
            json["suggests"] = suggestions
        } else {
            json.remove("suggests")
        }

        metadataFile.writeText(
            JsonOutput.prettyPrint(JsonOutput.toJson(json)) + "\n",
            Charsets.UTF_8,
        )
    }
    doLast {
        val accessWidener = destinationDir.resolve("quick-skin.accesswidener")
        check(accessWidener.isFile) { "Processed Fabric access widener is missing: $accessWidener" }
        val normalized = accessWidener.readText(Charsets.UTF_8).replace("\r\n", "\n")
        val lines = normalized.lines().toMutableList()
        check(lines.isNotEmpty()) { "Processed Fabric access widener is empty: $accessWidener" }
        val header = lines.first().split('\t')
        check(header.size == 3 && header[0] == "accessWidener" && header[1] == "v2"
                && header[2] == "official") {
            "Canonical Fabric access widener must use the official v2 namespace: $accessWidener"
        }
        // Loom's remap task consumes named input and emits intermediary output. No-remap lanes
        // package Mojang's official namespace directly.
        if (!isNoRemap) lines[0] = "accessWidener\tv2\tnamed"
        accessWidener.writeText(lines.joinToString("\n"), Charsets.UTF_8)
    }
}

if (isNoRemap) {
    apply(from = rootProject.file("gradle/no-remap-shadow-conventions.gradle.kts"))
} else {
    tasks.named<ShadowJar>("shadowJar") {
        configurations = listOf(project.configurations["shadowBundle"])
        archiveClassifier.set("dev-shadow")
    }

    tasks.named<net.fabricmc.loom.task.RemapJarTask>("remapJar") {
        dependsOn(tasks.named("shadowJar"))
        val shadowJar = tasks.named<ShadowJar>("shadowJar")
        mustRunAfter(shadowJar)
        inputFile.set(shadowJar.get().archiveFile)
    }
}

extensions.extraProperties["quickSkinE2ESourceSet"] = e2eSourceSet
extensions.extraProperties["quickSkinE2ELoaderLabel"] = "Fabric"
extensions.extraProperties["quickSkinE2ENoRemap"] = isNoRemap
extensions.extraProperties["quickSkinE2EMinecraftVersion"] = minecraftVersion
apply(from = rootProject.file("gradle/e2e-harness-conventions.gradle.kts"))
