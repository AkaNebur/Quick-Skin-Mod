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
    val awFile = file("src/main/resources/quick-skin.accesswidener")
    if (awFile.exists()) {
        accessWidenerPath.set(awFile)
    }
}

dependencies {
    modImplementation("net.fabricmc:fabric-loader:${project.versionProp("fabric_loader_version")}")
    modImplementation("dev.architectury:architectury:${project.versionProp("architectury_api_version")}")

    // WebP library only for 1.21.1
    if (minecraftVersion == "1.21.1") {
        implementation("org.sejda.imageio:webp-imageio:0.1.6")
    }
}
