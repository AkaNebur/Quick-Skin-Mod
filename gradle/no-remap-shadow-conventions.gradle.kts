import org.gradle.jvm.tasks.Jar

// Loom's no-remap branch still needs a raw main JAR as the input to Shadow. Shadow's public copy
// spec retains the original source, so clear it once here before adding the raw JAR and bundled
// dependencies. Any future no-remap loader lane must publish the resulting Shadow artifact.
tasks.named<Jar>("jar") {
    archiveClassifier.set("raw")
}

tasks.named<Jar>("shadowJar") {
    dependsOn(tasks.named("jar"))
    val mainSpec = generateSequence<Class<*>>(this.javaClass) { it.superclass }
        .first { it.name == "org.gradle.api.tasks.AbstractCopyTask" }
        .getDeclaredMethod("getMainSpec").also { it.isAccessible = true }
        .invoke(this)
    @Suppress("UNCHECKED_CAST")
    (mainSpec.javaClass.getMethod("getSourcePaths").invoke(mainSpec)
        as MutableCollection<Any?>).clear()

    from(zipTree(tasks.named<Jar>("jar").flatMap { it.archiveFile }))
    javaClass.getMethod("setConfigurations", List::class.java)
        .invoke(this, listOf(project.configurations["shadowBundle"]))
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
