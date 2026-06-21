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

val minecraftVersion = project.findProperty("minecraft_version") as String
val versionDir = "v${minecraftVersion.replace(".", "_")}"

// Minecraft 26.1+ is unobfuscated and built with the non-remapping Loom plugin.
val isNoRemap = minecraftVersion.startsWith("26.")
val modImpl = if (isNoRemap) "implementation" else "modImplementation"
val primaryJarTask = if (isNoRemap) "shadowJar" else "remapJar"

architectury {
    platformSetupLoomIde()
    neoForge()
}

sourceSets {
    main {
        java.srcDir("src/$versionDir/java")
        resources.srcDir("src/$versionDir/resources")
    }
}

repositories {
    maven("https://maven.neoforged.net/releases")
}

// ===== E2E dev-only harness (never enters the published jar) =====
// Shared core compiled from ../common/src/e2e into this module's `e2e` source set; added only to the
// E2E client run configs below (never to shadowBundle/remapJar/shadowJar), so it is absent from
// build/libs/*.jar. The shared source set must COMPILE against every enabled version, so the set is
// the NeoForge versions actually validated by the orchestrator (NeoForge is 1.21.x/26.x only).
val e2eEnabledVersions = setOf("1.21.1", "26.2")
if (minecraftVersion in e2eEnabledVersions) {
    val mainSs = sourceSets["main"]
    val e2eSs = sourceSets.create("e2e") {
        // setSrcDirs (not srcDir) so we don't re-add the default src/e2e dirs and duplicate the manifest.
        java.setSrcDirs(listOf("src/e2e/java", "../common/src/e2e/java"))
        resources.setSrcDirs(listOf("src/e2e/resources", "../common/src/e2e/resources"))
        compileClasspath += mainSs.output + mainSs.compileClasspath
        runtimeClasspath += mainSs.output + mainSs.runtimeClasspath
    }

    extensions.configure<LoomGradleExtensionAPI>("loom") {
        // NeoForge dev only scans the exploded dirs in fml.modFolders, which Loom populates from this
        // block. Declaring any mod disables auto-detection, so the main mod is declared too. (common
        // loads as a classpath jar, so it needs no entry.)
        mods.create("quickskin") { sourceSet(mainSs) }
        mods.create("quick_skin_e2e") { sourceSet(e2eSs) }

        runs {
            create("serverE2E") {
                server()
                name("Quick Skin E2E Server (NeoForge)")
                runDir("run/server-e2e")
                property("quickskin.e2e.role", "server")
            }
            // Scenario selected by the orchestrator via -Pe2e_scenario; same configs serve every scenario.
            create("clientAE2E") {
                client()
                name("Quick Skin E2E Client A (NeoForge)")
                runDir("run/clientA")
                source(e2eSs)
                property("quickskin.e2e.enabled", "true")
                property("quickskin.e2e.role", "client_a")
                property("quickskin.e2e.scenario", (project.findProperty("e2e_scenario") ?: "phase0-smoke").toString())
                property("quickskin.e2e.version", versionDir)
                // Skip FML's early-display GLFW window (intermittent macOS/headless primary-monitor flake
                // when a second client window opens right after the first; the real game window still opens).
                property("fml.earlyprogresswindow", "false")
                programArgs(
                    "--username", "Alice",
                    "--quickPlayMultiplayer", "localhost:25565",
                    "--width", "1280", "--height", "720"
                )
            }
            create("clientBE2E") {
                client()
                name("Quick Skin E2E Client B (NeoForge)")
                runDir("run/clientB")
                source(e2eSs)
                property("quickskin.e2e.enabled", "true")
                property("quickskin.e2e.role", "client_b")
                property("quickskin.e2e.scenario", (project.findProperty("e2e_scenario") ?: "propagation").toString())
                property("quickskin.e2e.version", versionDir)
                property("fml.earlyprogresswindow", "false")
                programArgs(
                    "--username", "Bob",
                    "--quickPlayMultiplayer", "localhost:25565",
                    "--width", "1280", "--height", "720"
                )
            }
        }
    }
}

configurations {
    create("common")
    create("shadowBundle")
    compileClasspath.get().extendsFrom(configurations["common"])
    runtimeClasspath.get().extendsFrom(configurations["common"])
    // Wire common into the dev-runtime configuration so the Architectury transformer applies
    // @ExpectPlatform to common classes when launching via runClient. findByName tolerates the
    // no-remap plugin not creating it.
    findByName("developmentNeoForge")?.extendsFrom(configurations["common"])
}

dependencies {
    "neoForge"("net.neoforged:neoforge:${project.versionProp("neoforge_version")}")
    modImpl("dev.architectury:architectury-neoforge:${project.versionProp("architectury_api_version")}")

    if (isNoRemap) {
        "common"(project(path = ":common")) { isTransitive = false }
    } else {
        "common"(project(path = ":common", configuration = "namedElements")) { isTransitive = false }
    }
    "shadowBundle"(project(path = ":common", configuration = "transformProductionNeoForge"))
    "shadowBundle"("org.sejda.imageio:webp-imageio:0.1.6")
}

tasks.processResources {
    inputs.property("version", project.version)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand("version" to project.version)
    }
}

if (isNoRemap) {
    tasks.named<Jar>("jar") {
        archiveClassifier.set("raw")
    }

    tasks.named<ShadowJar>("shadowJar") {
        dependsOn(tasks.named("jar"))
        // Clear shadow's default source-set content so only the Loom-finalized `jar` is packaged.
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
        dependsOn("shadowJar")
        val shadowJar = tasks.named<ShadowJar>("shadowJar")
        mustRunAfter(shadowJar)
        inputFile.set(shadowJar.get().archiveFile)
    }
}

// ===== PUBLISHING CONFIGURATION =====

val mcVersion = minecraftVersion
val supportedGameVersions = listOf(mcVersion)
val modLoaders = listOf("neoforge")

val changelogFile = rootProject.file(rootProject.property("changelog_file") as String)
val changelogText = if (changelogFile.exists()) changelogFile.readText() else "No changelog provided"

val modrinthToken: String? = findProperty("modrinth_token") as String? ?: System.getenv("MODRINTH_TOKEN")
val curseforgeToken: String? = findProperty("curseforge_token") as String? ?: System.getenv("CURSEFORGE_TOKEN")

modrinth {
    token.set(modrinthToken ?: "")
    projectId.set(rootProject.property("modrinth_id") as String)
    versionNumber.set("${project.version}")
    versionName.set("Quick Skin ${project.version} [NeoForge] [MC $mcVersion]")
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
