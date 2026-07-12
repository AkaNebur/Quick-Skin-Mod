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
val versionDir = "v${minecraftVersion.replace(".", "_")}"
val isNoRemap = minecraftVersion.startsWith("26.")
val legacyJavaRoot = rootProject.file("fabric/src/legacy1_20_1/java")
val generatedStonecutterJava = layout.buildDirectory.dir("generated/stonecutter/main/java")
val consolidatedLegacyJava = layout.buildDirectory.dir("generated/consolidated/main/java")
val commonProjectPath = requireNotNull(stonecutter.node.sibling("common")).hierarchy.toString()
val commonProject = project(commonProjectPath)
evaluationDependsOn(commonProjectPath)

val releaseMatrixFile = rootProject.file("release/release-matrix.json")
check(releaseMatrixFile.isFile) { "Missing central release matrix: $releaseMatrixFile" }
val releaseMatrix = JsonSlurper().parse(releaseMatrixFile) as Map<*, *>
val releaseArtifacts = (releaseMatrix["artifacts"] as List<*>).map { it as Map<*, *> }
val canonicalVersions = releaseArtifacts
    .filter { it["loader"] == "fabric" }
    .map { it["artifact_version"].toString() }
    .toSet()
val releaseProject = releaseMatrix["project"] as Map<*, *>
val releaseArtifact = releaseArtifacts
    .single { it["artifact_node"] == "fabric-$minecraftVersion" }
val releaseMetadata = releaseArtifact["metadata"] as Map<*, *>

fun matrixString(values: Map<*, *>, key: String): String =
    requireNotNull(values[key]) { "Missing '$key' in $releaseMatrixFile" }.toString()

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

if (minecraftVersion == "1.20.1") {
    val legacyOverrides = fileTree(legacyJavaRoot) {
        include("**/*.java")
    }.files.mapTo(linkedSetOf()) {
        it.relativeTo(legacyJavaRoot).invariantSeparatorsPath
    }
    val prepareConsolidatedJava = tasks.register<Sync>("prepareConsolidatedJava") {
        dependsOn("stonecutterGenerate")
        from(generatedStonecutterJava) {
            exclude(legacyOverrides)
        }
        from(legacyJavaRoot)
        into(consolidatedLegacyJava)
    }

    sourceSets {
        main {
            java.setSrcDirs(listOf(consolidatedLegacyJava))
            resources.setSrcDirs(listOf(rootProject.file("fabric/src/legacy1_20_1/resources")))
        }
    }

    tasks.named("compileJava") {
        dependsOn(prepareConsolidatedJava)
    }
    tasks.matching { it.name == "sourcesJar" }.configureEach {
        dependsOn(prepareConsolidatedJava)
    }

    tasks.processResources {
        from(rootProject.file("fabric/src/main/resources")) {
            include("icon.png", "quick-skin.accesswidener")
        }
    }
} else if (minecraftVersion == "1.21.1" || minecraftVersion == "1.21.11" || minecraftVersion == "26.1.2") {
    val prepareConsolidatedJava = tasks.register<Sync>("prepareConsolidatedJava") {
        dependsOn("stonecutterGenerate")
        from(generatedStonecutterJava)
        into(consolidatedLegacyJava)
    }

    sourceSets {
        main {
            java.setSrcDirs(listOf(consolidatedLegacyJava))
            resources.setSrcDirs(listOf(rootProject.file("fabric/src/$versionDir/resources")))
        }
    }

    tasks.named("compileJava") {
        dependsOn(prepareConsolidatedJava)
    }
    tasks.matching { it.name == "sourcesJar" }.configureEach {
        dependsOn(prepareConsolidatedJava)
    }

    tasks.processResources {
        from(rootProject.file("fabric/src/main/resources")) {
            include("icon.png", "quick-skin.accesswidener")
        }
    }
} else if (minecraftVersion !in canonicalVersions) {
    sourceSets {
        main {
            java.setSrcDirs(
                listOf(
                    rootProject.file("fabric/src/main/java/com/quickskin/mod/fabric"),
                    rootProject.file("fabric/src/$versionDir/java"),
                )
            )
            resources.setSrcDirs(listOf(rootProject.file("fabric/src/$versionDir/resources")))
        }
    }

    tasks.processResources {
        from(rootProject.file("fabric/src/main/resources")) {
            include("icon.png", "quick-skin.accesswidener")
        }
    }
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

        json["name"] = matrixString(releaseProject, "name")
        json["description"] = matrixString(releaseProject, "description")
        json["license"] = matrixString(releaseProject, "license")
        contact["homepage"] = matrixString(releaseProject, "homepage")
        contact["sources"] = matrixString(releaseProject, "sources")
        contact["issues"] = matrixString(releaseProject, "issues")
        depends["minecraft"] = matrixString(releaseMetadata, "minecraft")
        depends["architectury"] = matrixString(releaseMetadata, "architectury")
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
    if (minecraftVersion == "1.21.1" || minecraftVersion == "26.1.2") {
        doLast {
            val accessWidener = destinationDir.resolve("quick-skin.accesswidener")
            val normalized = accessWidener.readText()
                .replace("\r\n", "\n")
                .replace("\n", "\r\n")
            accessWidener.writeText(normalized)
        }
    }
}

if (isNoRemap) {
    tasks.named<Jar>("jar") {
        archiveClassifier.set("raw")
    }

    tasks.named<ShadowJar>("shadowJar") {
        dependsOn(tasks.named("jar"))
        val mainSpec = generateSequence<Class<*>>(this.javaClass) { it.superclass }
            .first { it.name == "org.gradle.api.tasks.AbstractCopyTask" }
            .getDeclaredMethod("getMainSpec").also { it.isAccessible = true }
            .invoke(this)
        @Suppress("UNCHECKED_CAST")
        (mainSpec.javaClass.getMethod("getSourcePaths").invoke(mainSpec) as MutableCollection<Any?>).clear()

        from(zipTree(tasks.named<Jar>("jar").flatMap { it.archiveFile }))
        configurations = listOf(project.configurations["shadowBundle"])
        archiveClassifier.set("")
    }

    configurations {
        named("apiElements") {
            outgoing.artifacts.clear()
            outgoing.artifact(tasks.named("shadowJar"))
        }
        named("runtimeElements") {
            outgoing.artifacts.clear()
            outgoing.artifact(tasks.named("shadowJar"))
        }
    }
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

val e2eArchiveBaseName = "Quick Skin E2E - Fabric - $minecraftVersion"
if (isNoRemap) {
    tasks.register<Jar>("e2eHarnessJar") {
        group = "verification"
        description = "Packages the client-only E2E harness without production Quick Skin classes."
        dependsOn(tasks.named(e2eSourceSet.classesTaskName))
        from(e2eSourceSet.output)
        archiveBaseName.set(e2eArchiveBaseName)
        archiveVersion.set("0.0.0")
        archiveClassifier.set("")
    }
} else {
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
        description = "Remaps the separate E2E harness for a real Fabric production runtime."
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
}
