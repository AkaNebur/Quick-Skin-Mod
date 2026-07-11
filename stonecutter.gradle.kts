import dev.kikugie.stonecutter.controller.flag.StonecutterFlag
import dev.architectury.plugin.ArchitectPluginExtension
import dev.architectury.plugin.loom.LoomInterface
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.build.mixin.AnnotationProcessorInvoker
import net.fabricmc.loom.util.gradle.SourceSetHelper
import org.gradle.jvm.tasks.Jar
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy

plugins {
    id("dev.kikugie.stonecutter")
    id("dev.architectury.loom") version "1.17.480" apply false
    id("dev.architectury.loom-no-remap") version "1.17.480" apply false
    id("architectury-plugin") version "3.5.167" apply false
    id("com.gradleup.shadow") version "8.3.11" apply false
    id("com.modrinth.minotaur") version "2.9.0" apply false
    id("net.darkhax.curseforgegradle") version "1.1.18" apply false
}

// Detached mode preprocesses each branch's canonical src/main tree into every node's generated
// sources without rewriting tracked files. Phase 1a consumes that output for 26.2; older nodes
// continue to replace their source sets with the read-only src/v* parity trees.
stonecutter active null

stonecutter {
    flags {
        set(StonecutterFlag.GENERATE_MANIFEST, false)
    }
}

// Stonecutter normally annotates every Jar manifest with node metadata. The parity gates require the
// production resources to match the legacy oracle, so remove only those four generated keys after
// every plugin has finished configuring the manifest.
allprojects {
    tasks.withType<Jar>().configureEach {
        doFirst {
            manifest.attributes.keys
                .filter { it.startsWith("Stonecutter-") }
                .forEach { manifest.attributes.remove(it) }
        }
    }
}

// Architectury's dev-run transformer asks classic Loom for mixin mappings from every Loom
// project in the build. Its global scan reaches the 26.2 no-remap nodes and fails before a
// run configuration can launch. Wrap only the classic loader projects' Loom interface and
// filter that one global query with the same mapping-identifier rule used by production
// transforms. All other Loom operations delegate unchanged.
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

tasks.register("buildAllLanes") {
    group = "build"
    description = "Builds every production Quick Skin loader artifact in one Gradle invocation."
    dependsOn(
        ":fabric:1.20.1:remapJar",
        ":forge:1.20.1:remapJar",
        ":fabric:1.21.1:remapJar",
        ":neoforge:1.21.1:remapJar",
        ":fabric:1.21.11:remapJar",
        ":neoforge:1.21.11:remapJar",
        ":fabric:26.2:shadowJar",
        ":neoforge:26.2:shadowJar",
    )
}
