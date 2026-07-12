import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import dev.architectury.plugin.ArchitectPluginExtension
import groovy.json.JsonSlurper
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import org.gradle.api.tasks.Sync

plugins {
    java
    `maven-publish`
}

val minecraftVersion = stonecutter.current.version
val versionDir = "v${minecraftVersion.replace(".", "_")}"
val canonicalVersions = setOf("1.21.1", "1.21.11", "26.1.2", "26.2")
val isNoRemap = minecraftVersion.startsWith("26.")
val legacy12111JavaRoot = rootProject.file("neoforge/src/legacy1_21_11/java")
val legacy1211JavaRoot = rootProject.file("neoforge/src/legacy1_21_1/java")
val legacy2612JavaRoot = rootProject.file("neoforge/src/legacy26_1_2/java")
val generatedStonecutterJava = layout.buildDirectory.dir("generated/stonecutter/main/java")
val consolidatedLegacyJava = layout.buildDirectory.dir("generated/consolidated/main/java")
val commonProjectPath = requireNotNull(stonecutter.node.sibling("common")).hierarchy.toString()
val commonProject = project(commonProjectPath)
evaluationDependsOn(commonProjectPath)

val releaseMatrixFile = rootProject.file("release/release-matrix.json")
check(releaseMatrixFile.isFile) { "Missing central release matrix: $releaseMatrixFile" }
val releaseMatrix = JsonSlurper().parse(releaseMatrixFile) as Map<*, *>
val releaseProject = releaseMatrix["project"] as Map<*, *>
val releaseArtifact = (releaseMatrix["artifacts"] as List<*>)
    .map { it as Map<*, *> }
    .single { it["artifact_node"] == "neoforge-$minecraftVersion" }
val releaseMetadata = releaseArtifact["metadata"] as Map<*, *>

fun matrixString(values: Map<*, *>, key: String): String =
    requireNotNull(values[key]) { "Missing '$key' in $releaseMatrixFile" }.toString()

fun normalizeNeoForgeToml(file: File) {
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
        "Unexpected NeoForge metadata shape in $file (license=$licenseCount, issues=$issueCount, " +
            "name=$displayNameCount, URL=$displayUrlCount, minecraft=$minecraftRangeCount, " +
            "architectury=$architecturyRangeCount, description=$descriptionCount)"
    }
    file.writeText(text, Charsets.UTF_8)
}

apply(plugin = if (isNoRemap) "dev.architectury.loom-no-remap" else "dev.architectury.loom")
apply(plugin = "architectury-plugin")
apply(plugin = "com.gradleup.shadow")

fun Project.versionProp(base: String): String =
    rootProject.property("${base}_${minecraftVersion.replace(".", "_")}") as String

group = rootProject.property("maven_group") as String
version = rootProject.property("mod_version") as String

extensions.configure<BasePluginExtension>("base") {
    archivesName.set("Quick Skin - NeoForge - $minecraftVersion")
}

extensions.configure<ArchitectPluginExtension>("architectury") {
    minecraft = minecraftVersion
    platformSetupLoomIde()
    neoForge()
}

repositories {
    mavenCentral()
    maven("https://maven.neoforged.net/releases")
}

if (minecraftVersion == "1.21.1" || minecraftVersion == "1.21.11" || minecraftVersion == "26.1.2") {
    val legacyJavaRoot = when (minecraftVersion) {
        "1.21.1" -> legacy1211JavaRoot
        "1.21.11" -> legacy12111JavaRoot
        else -> legacy2612JavaRoot
    }
    val canonicalOnlyAfter1211 = if (minecraftVersion == "1.21.1") {
        setOf("com/quickskin/mod/neoforge/mixin/GuiSkinRendererMixin.java")
    } else {
        emptySet()
    }
    val legacyOverrides = fileTree(legacyJavaRoot) {
        include("**/*.java")
    }.files.mapTo(linkedSetOf()) {
        it.relativeTo(legacyJavaRoot).invariantSeparatorsPath
    }
    val prepareConsolidatedJava = tasks.register<Sync>("prepareConsolidatedJava") {
        dependsOn("stonecutterGenerate")
        from(generatedStonecutterJava) {
            exclude(legacyOverrides + canonicalOnlyAfter1211)
        }
        from(legacyJavaRoot)
        into(consolidatedLegacyJava)
    }

    sourceSets {
        main {
            java.setSrcDirs(listOf(consolidatedLegacyJava))
            resources.setSrcDirs(listOf(rootProject.file("neoforge/src/$versionDir/resources")))
        }
    }

    tasks.named("compileJava") {
        dependsOn(prepareConsolidatedJava)
    }
    tasks.matching { it.name == "sourcesJar" }.configureEach {
        dependsOn(prepareConsolidatedJava)
    }
} else if (minecraftVersion !in canonicalVersions) {
    sourceSets {
        main {
            java.setSrcDirs(listOf(rootProject.file("neoforge/src/$versionDir/java")))
            resources.setSrcDirs(listOf(rootProject.file("neoforge/src/$versionDir/resources")))
        }
    }
}

// The harness is a separate client-only test mod. It compiles against this node's named output but
// packages only `src/e2e`; the exact production Quick Skin JAR is installed independently at runtime.
val mainSourceSet = sourceSets.named("main").get()
val e2eSourceSet = sourceSets.create("e2e") {
    java.setSrcDirs(
        listOf(
            rootProject.file("neoforge/src/e2e/java"),
            rootProject.file("common/src/e2e/java"),
        )
    )
    resources.setSrcDirs(
        listOf(
            rootProject.file("neoforge/src/e2e/resources"),
            rootProject.file("common/src/e2e/resources"),
        )
    )
    compileClasspath += mainSourceSet.output + mainSourceSet.compileClasspath
    runtimeClasspath += output + compileClasspath
}

configurations {
    create("common")
    create("shadowBundle")
    compileClasspath.get().extendsFrom(configurations["common"])
    runtimeClasspath.get().extendsFrom(configurations["common"])
    findByName("developmentNeoForge")?.extendsFrom(configurations["common"])
}

dependencies {
    "minecraft"("net.minecraft:minecraft:${versionProp("minecraft_version")}")
    if (!isNoRemap) {
        "mappings"(project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())
    }
    "neoForge"("net.neoforged:neoforge:${versionProp("neoforge_version")}")

    val modImpl = if (isNoRemap) "implementation" else "modImplementation"
    modImpl("dev.architectury:architectury-neoforge:${versionProp("architectury_api_version")}")

    "common"(project.files(commonProject.tasks.named("jar")))
    "shadowBundle"(project.files(commonProject.tasks.named("transformProductionNeoForge")))
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

tasks.withType<Jar>().configureEach {
    manifest.attributes.keys.filter { it.startsWith("Stonecutter-") }.forEach {
        manifest.attributes.remove(it)
    }
    doFirst {
        manifest.attributes.keys.filter { it.startsWith("Stonecutter-") }.forEach {
            manifest.attributes.remove(it)
        }
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    inputs.file(releaseMatrixFile)
    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to project.version)
    }
    doLast {
        val metadataFile = destinationDir.resolve("META-INF/neoforge.mods.toml")
        check(metadataFile.isFile) { "Processed NeoForge metadata is missing: $metadataFile" }
        normalizeNeoForgeToml(metadataFile)
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

val e2eArchiveBaseName = "Quick Skin E2E - NeoForge - $minecraftVersion"
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
        description = "Remaps the separate E2E harness for a real NeoForge production runtime."
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
