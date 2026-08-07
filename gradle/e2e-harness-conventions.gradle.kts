import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.tasks.Jar

val projectCoordinates = project.path.split(':').filter(String::isNotEmpty)
check(projectCoordinates.size >= 2) {
    "E2E harness project path must identify loader and Minecraft version: ${project.path}"
}
val loaderId = projectCoordinates.first()
val minecraftVersion = projectCoordinates.last()
val loaderLabel = mapOf(
    "fabric" to "Fabric",
    "forge" to "Forge",
    "neoforge" to "NeoForge",
)[loaderId] ?: error("Unsupported E2E harness loader in ${project.path}: $loaderId")
@Suppress("UNCHECKED_CAST")
val releaseArtifacts = gradle.extensions.extraProperties["quickSkinReleaseArtifacts"]
    as List<Map<*, *>>
val releaseArtifact = releaseArtifacts.singleOrNull {
    it["artifact_node"] == "$loaderId-$minecraftVersion"
} ?: error("Release matrix has no unique E2E artifact for $loaderId-$minecraftVersion")
val isNoRemap = releaseArtifact["no_remap"] as? Boolean
    ?: error("E2E artifact $loaderId-$minecraftVersion has no boolean no_remap")

// This protected convention is the authority for harness inputs. Older release build scripts may
// predeclare an `e2e` SourceSet, but its roots and classpaths are replaced here rather than trusted.
val sourceSetContainer = extensions.getByType(SourceSetContainer::class.java)
val mainSourceSet = sourceSetContainer.named("main").get()
val e2eSourceSet =
    sourceSetContainer.findByName("e2e") ?: sourceSetContainer.create("e2e")
val loaderE2EJava = rootProject.file("$loaderId/src/e2e/java")
val loaderE2EResources = rootProject.file("$loaderId/src/e2e/resources")
val commonE2EJava = rootProject.file("common/src/e2e/java")
val commonE2EResources = rootProject.file("common/src/e2e/resources")
listOf(loaderE2EJava, loaderE2EResources, commonE2EJava, commonE2EResources).forEach {
    check(it.isDirectory) { "Missing protected E2E harness source root: $it" }
}
e2eSourceSet.apply {
    java.setSrcDirs(listOf(loaderE2EJava, commonE2EJava))
    resources.setSrcDirs(listOf(loaderE2EResources, commonE2EResources))
    compileClasspath = files(mainSourceSet.output, mainSourceSet.compileClasspath)
    runtimeClasspath = files(output, compileClasspath)
}
val e2eArchiveBaseName = "Quick Skin E2E - $loaderLabel - $minecraftVersion"
val scenarioContractFile = rootProject.file("e2e/scenario-contract.json")
val scenarioContractValidator = rootProject.file("e2e/scenario_contract.py")
val scenarioContractGenerator = rootProject.file("e2e/generate_contract_java.py")
val generatedE2EContractJava = layout.buildDirectory.dir("generated/e2e-contract/java")
val defaultPython = if (
    System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
) "python" else "python3"
val contractPython = providers.environmentVariable("QUICKSKIN_PYTHON").orElse(defaultPython)

val generateE2EContractJava = tasks.register<Exec>("generateE2EContractJava") {
    group = "verification"
    description =
        "Validates the canonical E2E contract and generates its typed Java identities."
    inputs.files(
        scenarioContractFile,
        scenarioContractValidator,
        scenarioContractGenerator,
    )
    inputs.property("pythonExecutable", contractPython)
    val outputFile = generatedE2EContractJava.map {
        it.file("com/quickskin/mod/e2e/generated/ScenarioContract.java")
    }
    outputs.file(outputFile)
    workingDir(rootProject.projectDir)
    commandLine(
        contractPython.get(),
        scenarioContractGenerator.absolutePath,
        "--contract",
        scenarioContractFile.absolutePath,
        "--output",
        outputFile.get().asFile.absolutePath,
    )
}

e2eSourceSet.java.srcDir(generatedE2EContractJava)
tasks.named(e2eSourceSet.compileJavaTaskName) {
    dependsOn(generateE2EContractJava)
}

fun taskProperty(task: Task, getter: String): Any =
    task.javaClass.getMethod(getter).invoke(task)

if (isNoRemap) {
    tasks.register<Jar>("e2eHarnessJar") {
        group = "verification"
        description = "Packages the client-only E2E harness without production Quick Skin classes."
        dependsOn(tasks.named(e2eSourceSet.classesTaskName))
        from(e2eSourceSet.output)
        archiveBaseName.set(e2eArchiveBaseName)
        archiveVersion.set("0.0.0")
        archiveClassifier.set("")
    }
} else {
    val e2eHarnessDevJar = tasks.register<Jar>("e2eHarnessDevJar") {
        group = "verification"
        description = "Packages the named intermediary input for the remapped E2E harness."
        dependsOn(tasks.named(e2eSourceSet.classesTaskName))
        from(e2eSourceSet.output)
        archiveBaseName.set(e2eArchiveBaseName)
        archiveVersion.set("0.0.0")
        archiveClassifier.set("dev")
        destinationDirectory.set(layout.buildDirectory.dir("devlibs"))
    }
    val productionRemap = tasks.named("remapJar")
    var publicRemapTaskType: Class<*>? = productionRemap.get().javaClass
    while (
        publicRemapTaskType != null &&
        publicRemapTaskType.name != "net.fabricmc.loom.task.RemapJarTask"
    ) {
        publicRemapTaskType = publicRemapTaskType.superclass
    }
    check(publicRemapTaskType != null) {
        "Loom remapJar does not expose the pinned public RemapJarTask API"
    }
    @Suppress("UNCHECKED_CAST")
    val remapTaskType = requireNotNull(publicRemapTaskType)
        .asSubclass(Task::class.java) as Class<Task>
    tasks.register("remapE2EHarnessJar", remapTaskType, Action<Task> {
        group = "verification"
        description =
            "Remaps the separate E2E harness for a real $loaderLabel production runtime."
        dependsOn(e2eHarnessDevJar)

        @Suppress("UNCHECKED_CAST")
        (taskProperty(this, "getInputFile") as RegularFileProperty)
            .set(e2eHarnessDevJar.flatMap { it.archiveFile })
        @Suppress("UNCHECKED_CAST")
        (taskProperty(this, "getSourceNamespace") as Property<String>).set(
            productionRemap.map { task ->
                @Suppress("UNCHECKED_CAST")
                (taskProperty(task, "getSourceNamespace") as Property<String>).get()
            }
        )
        @Suppress("UNCHECKED_CAST")
        (taskProperty(this, "getTargetNamespace") as Property<String>).set(
            productionRemap.map { task ->
                @Suppress("UNCHECKED_CAST")
                (taskProperty(task, "getTargetNamespace") as Property<String>).get()
            }
        )
        (taskProperty(this, "getClasspath") as ConfigurableFileCollection).from(
            productionRemap.map { task -> taskProperty(task, "getClasspath") },
            e2eSourceSet.compileClasspath,
        )
        listOf(
            "getAddNestedDependencies",
            "getReadMixinConfigsFromManifest",
            "getInjectAccessWidener",
            "getUseMixinAP",
        ).forEach { getter ->
            @Suppress("UNCHECKED_CAST")
            (taskProperty(this, getter) as Property<Boolean>).set(false)
        }
        (this as AbstractArchiveTask).apply {
            archiveBaseName.set(e2eArchiveBaseName)
            archiveVersion.set("0.0.0")
            archiveClassifier.set("")
        }
    })
}
