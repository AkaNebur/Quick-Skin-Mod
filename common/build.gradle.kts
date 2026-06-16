// Helper function
fun Project.versionProp(base: String): String {
    val minecraftVersion = project.findProperty("minecraft_version") as String
    val propName = "${base}_${minecraftVersion.replace(".", "_")}"
    return project.property(propName) as String
}

architectury {
    val enabledPlatforms = project.versionProp("enabled_platforms").split(",")
    common(enabledPlatforms)
}

// Dynamic source set selection
val minecraftVersion = project.findProperty("minecraft_version") as String
val versionDir = "v${minecraftVersion.replace(".", "_")}"

// 26.1+ uses the non-remapping Loom: mod dependencies are plain `implementation`, not `modImplementation`.
val isNoRemap = minecraftVersion.startsWith("26.")
val modImpl = if (isNoRemap) "implementation" else "modImplementation"

sourceSets {
    main {
        java {
            setSrcDirs(listOf("src/$versionDir/java"))
        }
        resources {
            setSrcDirs(listOf("src/main/resources", "src/$versionDir/resources"))
        }
    }
}

loom {
    // The access widener is written in the `official` namespace for unobfuscated 26.1+, so only apply
    // it on no-remap versions. Classic (<=1.21.11) versions never had/needed a project AW.
    if (isNoRemap) {
        val awFile = file("src/main/resources/quick-skin.accesswidener")
        if (awFile.exists()) {
            accessWidenerPath.set(awFile)
        }
    }
}

dependencies {
    modImpl("net.fabricmc:fabric-loader:${project.versionProp("fabric_loader_version")}")
    modImpl("dev.architectury:architectury:${project.versionProp("architectury_api_version")}")

    // WebP library for 1.21.1+
    if (minecraftVersion != "1.20.1") {
        implementation("org.sejda.imageio:webp-imageio:0.1.6")
    }
}
