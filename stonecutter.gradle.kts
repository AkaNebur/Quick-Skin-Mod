import dev.kikugie.stonecutter.controller.flag.StonecutterFlag
import dev.architectury.plugin.ArchitectPluginExtension
import dev.architectury.plugin.loom.LoomInterface
import groovy.json.JsonSlurper
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.build.mixin.AnnotationProcessorInvoker
import net.fabricmc.loom.util.gradle.SourceSetHelper
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

plugins {
    id("dev.kikugie.stonecutter")
    id("dev.architectury.loom") version "1.17.480" apply false
    id("dev.architectury.loom-no-remap") version "1.17.480" apply false
    id("architectury-plugin") version "3.5.167" apply false
    id("com.gradleup.shadow") version "8.3.11" apply false
}

// Detached mode preprocesses each branch's canonical src/main tree into every node's generated
// sources without rewriting tracked files. Active nodes consume that output, with only the narrow
// src/legacy* era overlays declared in the branch build scripts. The retained src/v* trees are the
// exact-version parity oracles for the five supported Minecraft targets and are never compiled.
stonecutter active null

stonecutter {
    flags {
        set(StonecutterFlag.GENERATE_MANIFEST, false)
    }
}

val releaseMatrixFile = file("release/release-matrix.json")
check(releaseMatrixFile.isFile) { "Missing central release matrix: $releaseMatrixFile" }
val releaseMatrix = JsonSlurper().parse(releaseMatrixFile) as Map<*, *>
val releaseArtifacts = (releaseMatrix["artifacts"] as? List<*>)
    ?.map { artifact -> artifact as? Map<*, *> ?: error("Invalid artifact row in $releaseMatrixFile") }
    ?: error("Missing artifact inventory in $releaseMatrixFile")
val releaseLaneCount = (releaseMatrix["lane_count"] as? Number)?.toInt()
    ?: error("Missing lane_count in $releaseMatrixFile")
val unitTestVersion = releaseMatrix["unit_test_version"]?.toString()
    ?: error("Missing unit_test_version in $releaseMatrixFile")
check(releaseArtifacts.size == releaseLaneCount) {
    "Release artifact inventory has ${releaseArtifacts.size} rows; expected lane_count=$releaseLaneCount"
}
val releaseNodeNames = releaseArtifacts.map { artifact ->
    val loader = artifact["loader"]?.toString() ?: error("Release artifact is missing loader")
    val version = artifact["artifact_version"]?.toString()
        ?: error("Release artifact is missing artifact_version")
    val node = artifact["artifact_node"]?.toString()
        ?: error("Release artifact is missing artifact_node")
    check(node == "$loader-$version") { "Release node $node does not match $loader $version" }
    val propertySuffix = version.replace(".", "_")
    check(rootProject.property("minecraft_version_$propertySuffix").toString() == version) {
        "Minecraft Gradle property disagrees with release lane $node"
    }
    check(rootProject.property("java_version_$propertySuffix").toString() == artifact["java"].toString()) {
        "Java Gradle property disagrees with release lane $node"
    }
    node
}
check(releaseNodeNames.distinct().size == releaseNodeNames.size) {
    "Duplicate release artifact node in $releaseMatrixFile"
}
check(releaseArtifacts.any { it["artifact_version"].toString() == unitTestVersion }) {
    "Unit-test version $unitTestVersion is not a release lane"
}
val releaseArtifactTasks = releaseArtifacts.map { artifact ->
    artifact["gradle_task"]?.toString() ?: error("Release artifact is missing gradle_task")
}
val releaseHarnessTasks = releaseArtifacts.map { artifact ->
    artifact["harness_task"]?.toString() ?: error("Release artifact is missing harness_task")
}
releaseArtifacts.forEachIndexed { index, artifact ->
    val loader = artifact["loader"].toString()
    val version = artifact["artifact_version"].toString()
    val prefix = ":$loader:$version:"
    val expectedProductionTask = prefix + if (version.startsWith("26.")) "shadowJar" else "remapJar"
    val expectedHarnessTask =
        prefix + if (version.startsWith("26.")) "e2eHarnessJar" else "remapE2EHarnessJar"
    check(releaseArtifactTasks[index] == expectedProductionTask) {
        "Release production task ${releaseArtifactTasks[index]} must be $expectedProductionTask"
    }
    check(releaseHarnessTasks[index] == expectedHarnessTask) {
        "Release harness task ${releaseHarnessTasks[index]} must be $expectedHarnessTask"
    }
}

// COMPATIBILITY QUARANTINE (Architectury Plugin 3.5.167): its dev-run transformer asks classic
// Loom for mixin mappings from every Loom project in the build. The global scan reaches 26.x
// no-remap nodes and fails before a run configuration can launch. There is no public extension
// point for this query in the pinned plugin, so this is the only block allowed to reflect into
// Architectury state. Remove it once upstream scopes getAllMixinMappings to compatible Loom
// projects. All other Loom operations delegate unchanged.
gradle.projectsEvaluated {
    allprojects
        .filter {
            it.pluginManager.hasPlugin("architectury-plugin") &&
                it.pluginManager.hasPlugin("dev.architectury.loom") &&
                !it.pluginManager.hasPlugin("dev.architectury.loom-no-remap")
        }
        .forEach { loomProject ->
            val extension = loomProject.extensions.getByType<ArchitectPluginExtension>()
            val lazyField = generateSequence<Class<*>>(extension.javaClass) { it.superclass }
                .mapNotNull { type ->
                    runCatching { type.getDeclaredField("loom\$delegate") }.getOrNull()
                }
                .first()
                .apply { isAccessible = true }
            @Suppress("UNCHECKED_CAST")
            val original = (lazyField.get(extension) as Lazy<LoomInterface>).value
            val filtered = Proxy.newProxyInstance(
                LoomInterface::class.java.classLoader,
                arrayOf(LoomInterface::class.java),
            ) { _, method, args ->
                if (method.name == "getAllMixinMappings" && method.parameterCount == 0) {
                    val currentMappings = LoomGradleExtension.get(loomProject)
                        .mappingConfiguration.mappingsIdentifier
                    val mixinMappings = mutableListOf<java.nio.file.Path>()
                    rootProject.allprojects
                        .filter {
                            it.pluginManager.hasPlugin("dev.architectury.loom") &&
                                !it.pluginManager.hasPlugin("dev.architectury.loom-no-remap")
                        }
                        .forEach { candidate ->
                            val candidateLoom = LoomGradleExtension.get(candidate)
                            if (candidateLoom.mappingConfiguration.mappingsIdentifier == currentMappings) {
                                SourceSetHelper.getSourceSets(candidate).forEach { sourceSet ->
                                    val mappingFile = AnnotationProcessorInvoker.getMixinMappingsForSourceSet(
                                        candidate,
                                        sourceSet,
                                    )
                                    if (mappingFile.exists()) mixinMappings.add(mappingFile.toPath())
                                }
                            }
                        }
                    mixinMappings
                } else {
                    try {
                        method.invoke(original, *(args ?: emptyArray()))
                    } catch (e: InvocationTargetException) {
                        throw e.targetException
                    }
                }
            } as LoomInterface
            lazyField.set(extension, lazy { filtered })
        }
}

val validateReleaseLaneInventory = tasks.register("validateReleaseLaneInventory") {
    group = "verification"
    description = "Checks that every central release-matrix lane resolves to real Gradle tasks."
    inputs.file(releaseMatrixFile)
    doLast {
        (releaseArtifactTasks + releaseHarnessTasks).forEach { taskPath ->
            tasks.getByPath(taskPath)
        }
    }
}

val testStableLane = tasks.register("testStableLane") {
    group = "verification"
    description = "Runs loader-independent JUnit tests on common $unitTestVersion."
    dependsOn(":common:$unitTestVersion:test")
}

tasks.register("check") {
    group = "verification"
    description = "Runs the central lane and loader-independent verification suite."
    dependsOn(validateReleaseLaneInventory, testStableLane)
}

tasks.register("buildAllLanes") {
    group = "build"
    description = "Builds all $releaseLaneCount production artifacts from the release matrix."
    dependsOn(validateReleaseLaneInventory, testStableLane, releaseArtifactTasks)
}

tasks.register("buildAllE2EHarnesses") {
    group = "verification"
    description = "Builds all $releaseLaneCount packaged-runtime E2E harnesses from the release matrix."
    dependsOn(validateReleaseLaneInventory, releaseHarnessTasks)
}
