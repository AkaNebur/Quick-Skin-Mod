import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.fabricmc.loom.api.LoomGradleExtensionAPI

plugins {
    id("com.gradleup.shadow")
    id("com.modrinth.minotaur")
    id("net.darkhax.curseforgegradle")
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

// Minecraft 26.1+ is unobfuscated and built with the non-remapping Loom plugin: mod deps are plain
// `implementation`, there is no `remapJar`, and `shadowJar` becomes the primary published artifact.
val isNoRemap = minecraftVersion.startsWith("26.")
val modImpl = if (isNoRemap) "implementation" else "modImplementation"
val primaryJarTask = if (isNoRemap) "shadowJar" else "remapJar"

sourceSets {
    main {
        java.srcDir("src/$versionDir/java")
        resources.srcDir("src/$versionDir/resources")
    }
}

// ===== E2E dev-only harness (never enters the published jar) =====
// The shared, loader-agnostic harness lives in ../common/src/e2e and is compiled directly into this
// module's `e2e` source set (no fragile cross-module classpath wiring). The source set is added ONLY
// to the E2E run configs below — never to shadowBundle / remapJar / shadowJar — so harness classes
// are physically absent from build/libs/*.jar (verified by unzip+grep). The shared source set must
// COMPILE against every enabled version (each MC version has its own copy of the mod classes the
// harness drives), so the enabled set is the versions actually validated by the orchestrator.
val e2eEnabledVersions = setOf("1.20.1", "1.21.1", "26.2")
if (minecraftVersion in e2eEnabledVersions) {
    val mainSs = sourceSets["main"]
    val e2eSs = sourceSets.create("e2e") {
        // setSrcDirs (not srcDir) so we don't re-add the default src/e2e dirs and duplicate fabric.mod.json.
        java.setSrcDirs(listOf("src/e2e/java", "../common/src/e2e/java"))
        resources.setSrcDirs(listOf("src/e2e/resources", "../common/src/e2e/resources"))
        // Inherit everything main has: Minecraft, fabric-loader/api, architectury, and the common
        // project's classes (services the harness drives) + fabric main classes.
        compileClasspath += mainSs.output + mainSs.compileClasspath
        runtimeClasspath += mainSs.output + mainSs.runtimeClasspath
    }

    extensions.configure<LoomGradleExtensionAPI>("loom") {
        runs {
            create("serverE2E") {
                server()
                name("Quick Skin E2E Server")
                runDir("run/server-e2e")
                // tag for orchestrator teardown targeting; harness stays inert (no e2e source here)
                property("quickskin.e2e.role", "server")
            }
            // Scenario is selected by the orchestrator via -Pe2e_scenario (default phase0-smoke for A,
            // propagation for B), so the same run configs serve Phase 0 (1 client) and Phase 1 (A+B).
            create("clientAE2E") {
                client()
                name("Quick Skin E2E Client A")
                runDir("run/clientA")
                source(e2eSs)
                property("quickskin.e2e.enabled", "true")
                property("quickskin.e2e.role", "client_a")
                property("quickskin.e2e.scenario", (project.findProperty("e2e_scenario") ?: "phase0-smoke").toString())
                property("quickskin.e2e.version", versionDir)
                programArgs(
                    "--username", "Alice",
                    "--quickPlayMultiplayer", "localhost:25565",
                    "--width", "1280", "--height", "720"
                )
            }
            create("clientBE2E") {
                client()
                name("Quick Skin E2E Client B")
                runDir("run/clientB")
                source(e2eSs)
                property("quickskin.e2e.enabled", "true")
                property("quickskin.e2e.role", "client_b")
                property("quickskin.e2e.scenario", (project.findProperty("e2e_scenario") ?: "propagation").toString())
                property("quickskin.e2e.version", versionDir)
                programArgs(
                    "--username", "Bob",
                    "--quickPlayMultiplayer", "localhost:25565",
                    "--width", "1280", "--height", "720"
                )
            }
        }
    }
}

// Apply the project access widener for no-remap (26.1+) dev runs, since Architectury's own
// bundled access widener is not applied in the Loom 1.17 no-remap dev runtime.
if (isNoRemap) {
    loom {
        val awFile = file("src/main/resources/quick-skin.accesswidener")
        if (awFile.exists()) accessWidenerPath.set(awFile)
    }
}

// Ensure src/main/java is still included (entry points live there)
// and version-specific java overrides (e.g. PlatformHelperImpl) come from src/$versionDir/java

configurations {
    create("common")
    create("shadowBundle")
    compileClasspath.get().extendsFrom(configurations["common"])
    runtimeClasspath.get().extendsFrom(configurations["common"])
    // Wire common into the dev-runtime configuration so the Architectury transformer applies
    // @ExpectPlatform to common classes when launching via runClient. findByName tolerates the
    // no-remap plugin not creating it.
    findByName("developmentFabric")?.extendsFrom(configurations["common"])
}

dependencies {
    modImpl("net.fabricmc:fabric-loader:${project.versionProp("fabric_loader_version")}")
    modImpl("net.fabricmc.fabric-api:fabric-api:${project.versionProp("fabric_api_version")}")
    modImpl("dev.architectury:architectury-fabric:${project.versionProp("architectury_api_version")}")

    // No-remap projects expose the common classes directly (no `namedElements` configuration).
    if (isNoRemap) {
        "common"(project(path = ":common")) { isTransitive = false }
    } else {
        "common"(project(path = ":common", configuration = "namedElements")) { isTransitive = false }
    }
    "shadowBundle"(project(path = ":common", configuration = "transformProductionFabric"))

    if (minecraftVersion != "1.20.1") {
        "shadowBundle"("org.sejda.imageio:webp-imageio:0.1.6")
    }
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("fabric.mod.json") {
        expand("version" to project.version)
    }
}

if (isNoRemap) {
    // Non-remapping build: `jar` carries the Loom-finalized mod (fabric.mod.json, JiJ); `shadowJar`
    // repackages it together with the bundled common classes and becomes the primary artifact.
    tasks.named<Jar>("jar") {
        archiveClassifier.set("raw")
    }

    tasks.named<ShadowJar>("shadowJar") {
        dependsOn(tasks.named("jar"))
        // Clear shadow's default source-set content so only the Loom-finalized `jar` is packaged
        // (mirrors shedaniel's official Architectury 26.1 migration, which calls mainSpec.sourcePaths.clear()).
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

    // Publish the shadowJar as the module's primary artifact.
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
        dependsOn("shadowJar")
        val shadowJar = tasks.named<ShadowJar>("shadowJar")
        mustRunAfter(shadowJar)
        inputFile.set(shadowJar.get().archiveFile)
    }
}

// ===== PUBLISHING CONFIGURATION =====

val mcVersion = minecraftVersion
val supportedGameVersions = listOf(mcVersion)

val modLoaders = listOf("fabric")

val changelogFile = rootProject.file(rootProject.property("changelog_file") as String)
val changelogText = if (changelogFile.exists()) changelogFile.readText() else "No changelog provided"

val modrinthToken: String? = findProperty("modrinth_token") as String? ?: System.getenv("MODRINTH_TOKEN")
val curseforgeToken: String? = findProperty("curseforge_token") as String? ?: System.getenv("CURSEFORGE_TOKEN")

modrinth {
    token.set(modrinthToken ?: "")
    projectId.set(rootProject.property("modrinth_id") as String)
    versionNumber.set("${project.version}")
    versionName.set("Quick Skin ${project.version} [Fabric] [MC $mcVersion]")
    versionType.set("release")
    uploadFile.set(tasks.named(primaryJarTask))
    gameVersions.addAll(supportedGameVersions)
    loaders.addAll(modLoaders)
    changelog.set(changelogText)
}

tasks.register<net.darkhax.curseforgegradle.TaskPublishCurseForge>("publishCurseForge") {
    dependsOn(tasks.named(primaryJarTask))
    apiToken = curseforgeToken ?: ""
    val mainFile = upload(rootProject.property("curseforge_id") as String, tasks.named(primaryJarTask).get().outputs.files.singleFile)
    mainFile.changelogType = "markdown"
    mainFile.changelog = changelogText
    mainFile.releaseType = "release"
    supportedGameVersions.forEach { mainFile.addGameVersion(it) }
    modLoaders.forEach { mainFile.addModLoader(it) }
    doFirst {
        if (curseforgeToken.isNullOrEmpty()) throw GradleException("curseforge_token not set!")
    }
}

tasks.register("publishAll") {
    group = "publishing"
    description = "Publishes to both Modrinth and CurseForge"
    dependsOn("modrinth", "publishCurseForge")
}
