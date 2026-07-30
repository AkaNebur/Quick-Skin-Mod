import org.gradle.jvm.tasks.Jar

// Stonecutter node projects do not reliably inherit archive configuration from the central root.
// Apply this script explicitly in each production branch so raw, shadow, remap-input, sources, and
// E2E jars all use the same reproducible settings and parity-safe manifest.
tasks.withType<Jar>().configureEach {
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
    manifest.attributes.keys
        .filter { it.startsWith("Stonecutter-") }
        .forEach { manifest.attributes.remove(it) }
    doFirst {
        manifest.attributes.keys
            .filter { it.startsWith("Stonecutter-") }
            .forEach { manifest.attributes.remove(it) }
    }
}
