import dev.architectury.plugin.ArchitectPluginExtension
import dev.architectury.plugin.TransformingTask
import dev.architectury.plugin.loom.LoomInterface117
import dev.architectury.transformer.shadowed.impl.com.google.gson.JsonObject
import dev.architectury.transformer.transformers.RemapInjectables
import dev.architectury.transformer.transformers.TransformExpectPlatform
import groovy.json.JsonSlurper
import net.fabricmc.loom.LoomGradleExtension
import net.fabricmc.loom.api.LoomGradleExtensionAPI
import net.fabricmc.loom.build.mixin.AnnotationProcessorInvoker
import net.fabricmc.loom.util.gradle.SourceSetHelper
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.tasks.Sync
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

plugins {
    java
    `maven-publish`
}

apply(from = rootProject.file("gradle/archive-conventions.gradle.kts"))

val minecraftVersion = stonecutter.current.version
val versionDir = "v${minecraftVersion.replace(".", "_")}"
val releaseMatrixFile = rootProject.file("release/release-matrix.json")
check(releaseMatrixFile.isFile) { "Missing central release matrix: $releaseMatrixFile" }
val releaseMatrix = JsonSlurper().parse(releaseMatrixFile) as Map<*, *>
val releaseArtifacts = (releaseMatrix["artifacts"] as List<*>).map { it as Map<*, *> }
val canonicalVersions = releaseArtifacts
    .map { it["artifact_version"].toString() }
    .toSet()
val isNoRemap = minecraftVersion.startsWith("26.")
val generatedStonecutterJava = layout.buildDirectory.dir("generated/stonecutter/main/java")
val consolidatedLegacyJava = layout.buildDirectory.dir("generated/consolidated/main/java")
val legacyCommonJar = rootProject.file(
    "common/build/${if (isNoRemap) "libs" else "devlibs"}/" +
        "Quick Skin - common - $minecraftVersion-${rootProject.property("mod_version")}-dev.jar"
).toPath()
// Architectury embeds this value in generated class names. Hash the stable, repository-relative
// artifact identity so identical sources produce the same classes on every checkout and OS.
val legacyArtifactIdentity = rootProject.projectDir.toPath()
    .relativize(legacyCommonJar)
    .toString()
    .replace(File.separatorChar, '/')
val legacyArtifactHash = MessageDigest.getInstance("SHA-256")
    .digest(legacyArtifactIdentity.toByteArray(StandardCharsets.UTF_8))
    .joinToString("") { "%02x".format(it) }
val legacyArchitecturyPackage = (
    "architectury_inject_quickskin_common_${rootProject.property("architectury_inject_id")}_" +
        "$legacyArtifactHash${legacyCommonJar.fileName}"
).filter { Character.isJavaIdentifierPart(it) }

apply(plugin = if (isNoRemap) "dev.architectury.loom-no-remap" else "dev.architectury.loom")
apply(plugin = "architectury-plugin")

fun Project.versionProp(base: String): String =
    rootProject.property("${base}_${minecraftVersion.replace(".", "_")}") as String

group = rootProject.property("maven_group") as String
version = rootProject.property("mod_version") as String

extensions.configure<BasePluginExtension>("base") {
    archivesName.set("Quick Skin - common - $minecraftVersion")
}

extensions.configure<ArchitectPluginExtension>("architectury") {
    minecraft = minecraftVersion
    common(
        releaseArtifacts
            .filter { it["artifact_version"].toString() == minecraftVersion }
            .map { it["loader"].toString() }
    )
}

repositories {
    mavenCentral()
}

val legacyOverlay = when (minecraftVersion) {
    "1.20.1" -> Triple(
        rootProject.file("common/src/legacy1_20_1/java"),
        rootProject.file("common/src/legacy1_20_1/resources"),
        setOf(
            "com/quickskin/mod/client/rendering/DeferredCollectorPreviewRenderBackend.java",
            "com/quickskin/mod/mixin/GuiSkinRendererMixin.java",
            "com/quickskin/mod/mixin/PlayerRendererMixin.java",
            "com/quickskin/mod/mixin/SkinManagerMixin.java",
            "com/quickskin/mod/networking/payloads/CooldownUpdatePayload.java",
            "com/quickskin/mod/networking/payloads/PayloadCodecs.java",
            "com/quickskin/mod/networking/payloads/RequestTexturePayload.java",
            "com/quickskin/mod/networking/payloads/SendAnimationMetadataPayload.java",
            "com/quickskin/mod/networking/payloads/SendTextureChunkPayload.java",
            "com/quickskin/mod/networking/payloads/SendTexturePayload.java",
            "com/quickskin/mod/networking/payloads/SyncAppearancePayload.java",
            "com/quickskin/mod/networking/payloads/SyncServerConfigPayload.java",
            "com/quickskin/mod/networking/payloads/TextureChunkPayload.java",
            "com/quickskin/mod/networking/payloads/UpdateAppearancePayload.java",
            "com/quickskin/mod/networking/payloads/UpdateServerConfigPayload.java",
            "com/quickskin/mod/networking/payloads/UploadAnimationMetadataPayload.java",
            "com/quickskin/mod/networking/payloads/UploadTexturePayload.java",
            "com/quickskin/mod/platform/MinecraftCompat26_2.java",
        ),
    )
    "1.21.1" -> Triple(
        rootProject.file("common/src/legacy1_21_1/java"),
        rootProject.file("common/src/v1_21_1/resources"),
        setOf(
            "com/quickskin/mod/client/rendering/DeferredCollectorPreviewRenderBackend.java",
            "com/quickskin/mod/mixin/GuiSkinRendererMixin.java",
            "com/quickskin/mod/platform/MinecraftCompat26_2.java",
        ),
    )
    "1.21.11" -> Triple(
        rootProject.file("common/src/legacy1_21_11/java"),
        rootProject.file("common/src/v1_21_11/resources"),
        setOf(
            "com/quickskin/mod/client/rendering/DeferredCollectorPreviewRenderBackend.java",
            "com/quickskin/mod/platform/MinecraftCompat26_2.java",
        ),
    )
    "26.1.2" -> Triple(
        rootProject.file("common/src/legacy26_1_2/java"),
        rootProject.file("common/src/v26_1_2/resources"),
        setOf("com/quickskin/mod/client/rendering/DeferredCollectorPreviewRenderBackend.java"),
    )
    else -> null
}

if (legacyOverlay != null) {
    val (legacyJavaRoot, legacyResourcesRoot, canonicalOnlyAfterLegacy) = legacyOverlay
    val legacyOverrides = fileTree(legacyJavaRoot) {
        include("**/*.java")
    }.files.mapTo(linkedSetOf()) {
        it.relativeTo(legacyJavaRoot).invariantSeparatorsPath
    }
    val prepareConsolidatedJava = tasks.register<Sync>("prepareConsolidatedJava") {
        dependsOn("stonecutterGenerate")
        from(generatedStonecutterJava) {
            exclude(legacyOverrides + canonicalOnlyAfterLegacy)
        }
        from(legacyJavaRoot)
        into(consolidatedLegacyJava)
    }

    sourceSets {
        main {
            java.setSrcDirs(listOf(consolidatedLegacyJava))
            resources.setSrcDirs(listOf(legacyResourcesRoot))
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
            java.setSrcDirs(listOf(rootProject.file("common/src/$versionDir/java")))
            resources.setSrcDirs(listOf(rootProject.file("common/src/$versionDir/resources")))
        }
    }
}

if (legacyOverlay != null || minecraftVersion !in canonicalVersions) {
    tasks.processResources {
        from(rootProject.file("common/src/main/resources")) {
            include("assets/quickskin/lang/**")
        }
    }
}

// Keep every exposed common:<version>:test task valid. CI uses unitTestVersion as its fast stable
// lane, while release verification may exercise the same loader-independent suite against every
// generated/legacy overlay to catch source-set drift.
sourceSets.test {
    java.setSrcDirs(listOf(rootProject.file("common/src/test/java")))
    resources.setSrcDirs(listOf(rootProject.file("common/src/test/resources")))
}

extensions.configure<LoomGradleExtensionAPI>("loom") {
    if (isNoRemap) {
        val awFile = rootProject.file("common/src/main/resources/quick-skin.accesswidener")
        if (awFile.exists()) accessWidenerPath.set(awFile)
    }
}

dependencies {
    "minecraft"("net.minecraft:minecraft:${versionProp("minecraft_version")}")
    if (!isNoRemap) {
        "mappings"(project.extensions.getByType<LoomGradleExtensionAPI>().officialMojangMappings())
    }

    val modImpl = if (isNoRemap) "implementation" else "modImplementation"
    modImpl("net.fabricmc:fabric-loader:${versionProp("fabric_loader_version")}")
    modImpl("dev.architectury:architectury:${versionProp("architectury_api_version")}")

    "implementation"("org.sejda.imageio:webp-imageio:0.1.6")

    "testImplementation"("org.junit.jupiter:junit-jupiter:5.13.4")
    "testRuntimeOnly"("org.junit.platform:junit-platform-launcher:1.13.4")
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

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// COMPATIBILITY QUARANTINE (Architectury Plugin 3.5.167): production transforms ask Loom for
// mixin mappings from every Loom project, including incompatible 26.x no-remap nodes. The pinned
// plugin exposes no public property-map hook, so keep its internal transformer types confined to
// this block. Remove it once upstream scopes the scan. Projects with another mapping identifier
// remain filtered exactly as Architectury does.
gradle.projectsEvaluated {
    tasks.withType<TransformingTask>().configureEach {
        val targetPlatform = when {
            name.endsWith("NeoForge") -> "neoforge"
            name.endsWith("Forge") -> "forge"
            else -> "fabric"
        }

        properties.set(providers.provider {
            val architecturyExtension = project.extensions.getByType<ArchitectPluginExtension>()
            val loomInterface = LoomInterface117(project)
            val transformerClasspath =
                (project.configurations.findByName("architecturyTransformerClasspath")
                    ?: project.configurations.getByName("compileClasspath"))

            val result = linkedMapOf(
                "architectury.inject.injectables" to architecturyExtension.injectInjectables.toString(),
                "architectury.unique.identifier" to legacyArchitecturyPackage,
                "architectury.classpath" to transformerClasspath.files.joinToString(File.pathSeparator),
                "architectury.platform.name" to targetPlatform,
                "architectury.mcmeta.version" to "4",
            )

            if (targetPlatform != "neoforge") {
                if (targetPlatform == "forge" && !loomInterface.addRefmapForForge) {
                    result["architectury.forge.fix_mixins"] = "false"
                } else if (loomInterface.legacyMixinApEnabled) {
                    result["architectury.refmap.name"] = loomInterface.refmapName
                }

                if (!loomInterface.disableObfuscation) {
                    result["architectury.srg.mappings"] = loomInterface.tinyMappingsWithSrg.toString()
                }
            }

            if (!loomInterface.disableObfuscation) {
                val currentMappings = LoomGradleExtension.get(project).mappingConfiguration.mappingsIdentifier
                val mixinMappings = mutableListOf<File>()

                rootProject.allprojects
                    .filter {
                        it.pluginManager.hasPlugin("dev.architectury.loom") &&
                            !it.pluginManager.hasPlugin("dev.architectury.loom-no-remap")
                    }
                    .forEach { loomProject ->
                        val loomExtension = LoomGradleExtension.get(loomProject)
                        if (loomExtension.mappingConfiguration.mappingsIdentifier == currentMappings) {
                            SourceSetHelper.getSourceSets(loomProject).forEach { sourceSet ->
                                val mappingFile = AnnotationProcessorInvoker.getMixinMappingsForSourceSet(
                                    loomProject,
                                    sourceSet,
                                )
                                if (mappingFile.exists()) mixinMappings += mappingFile
                            }
                        }
                    }

                result["architectury.mixin.mappings"] = mixinMappings.joinToString(File.pathSeparator)
            }

            result
        })
        properties.disallowChanges()
        val transformTask = this
        gradle.taskGraph.whenReady {
            if (hasTask(transformTask)) {
                val configuredTransformers = transformTask.transformers.get()
                val identity = JsonObject().apply {
                    addProperty(
                        "architectury.unique.identifier",
                        legacyArchitecturyPackage,
                    )
                }
                configuredTransformers.forEach {
                    if (it is TransformExpectPlatform || it is RemapInjectables) {
                        it.supplyProperties(identity)
                    }
                }
                transformTask.transformers.set(configuredTransformers)
            }
        }
    }
}

extensions.configure<PublishingExtension>("publishing") {
    publications {
        create<MavenPublication>("mavenJava") {
            artifactId = extensions.getByType<BasePluginExtension>().archivesName.get()
            from(components["java"])
        }
    }
}
