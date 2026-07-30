import org.gradle.api.Action
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.jvm.tasks.Jar

val conventionState = extensions.extraProperties
val e2eSourceSet = conventionState["quickSkinE2ESourceSet"] as SourceSet
val loaderLabel = conventionState["quickSkinE2ELoaderLabel"].toString()
val isNoRemap = conventionState["quickSkinE2ENoRemap"] as Boolean
val minecraftVersion = conventionState["quickSkinE2EMinecraftVersion"].toString()
val e2eArchiveBaseName = "Quick Skin E2E - $loaderLabel - $minecraftVersion"

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
    @Suppress("UNCHECKED_CAST")
    val remapTaskType = productionRemap.get().javaClass.asSubclass(Task::class.java) as Class<Task>
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
