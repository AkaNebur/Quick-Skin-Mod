import dev.kikugie.stonecutter.controller.flag.StonecutterFlag
import org.gradle.jvm.tasks.Jar

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

tasks.register("buildAllLanes") {
    group = "build"
    description = "Builds every production Quick Skin loader artifact in one Gradle invocation."
    dependsOn(
        ":fabric:1.20.1:remapJar",
        ":forge:1.20.1:remapJar",
        ":fabric:1.21.11:remapJar",
        ":neoforge:1.21.11:remapJar",
        ":fabric:26.2:shadowJar",
        ":neoforge:26.2:shadowJar",
    )
}
