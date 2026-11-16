import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import java.util.zip.GZIPOutputStream
import javax.inject.Inject

/**
 * Puts a watch APK into the assets of the phone app, so the phone app can update the watch app
 * over the Wear OS connection, without adb on the watch (`WatchAppUpdater` in `:plugins:sync`).
 *
 * Off by default. Build the watch app first, sign it with the key of the installed watch app, then:
 *
 *     ./gradlew :app:assembleFullRelease -PembedWearApk=/path/to/wear-full-release.apk
 *
 * The APK is stored gzip compressed (the dex files in an APK are not compressed, so it gets about
 * three times smaller), which also makes the transfer to the watch shorter. A variant whose
 * application id is not the package of the watch APK gets nothing: the watch would refuse it anyway.
 */
abstract class EmbedWatchAppTask @Inject constructor(private val execOperations: ExecOperations) : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val watchApk: RegularFileProperty

    /** aapt2 of the Android SDK build tools, to read the package name and version from the APK */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val aapt2: RegularFileProperty

    /** The application id of the phone variant; the watch app must have the same */
    @get:Input
    abstract val applicationId: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val apk = watchApk.get().asFile

        val badging = ByteArrayOutputStream()
        execOperations.exec {
            commandLine(aapt2.get().asFile.absolutePath, "dump", "badging", apk.absolutePath)
            standardOutput = badging
        }
        val packageLine = badging.toString().lineSequence().firstOrNull { it.startsWith("package:") }
            ?: throw GradleException("$apk is not an APK: aapt2 found no package")
        val packageName = attribute(packageLine, "name")
        val versionName = attribute(packageLine, "versionName") ?: "?"
        if (packageName != applicationId.get()) {
            logger.warn("Watch app ${apk.name} is $packageName, not ${applicationId.get()}: not embedded in this variant")
            return
        }

        val dir = File(out, ASSET_DIR).apply { mkdirs() }
        val gz = File(dir, APK_ASSET)
        GZIPOutputStream(gz.outputStream().buffered(), 64 * 1024).use { zip -> apk.inputStream().use { it.copyTo(zip) } }
        val info = Properties().apply {
            setProperty("label", "$versionName (${apk.name})")
            setProperty("size", gz.length().toString())
        }
        File(dir, INFO_ASSET).outputStream().use { info.store(it, "Watch app embedded by EmbedWatchAppTask") }
        logger.lifecycle("Watch app ${apk.name} ($versionName) embedded, ${gz.length() / 1024} KiB compressed")
    }

    private fun attribute(line: String, name: String): String? = Regex("""\b$name='([^']*)'""").find(line)?.groupValues?.get(1)

    companion object {

        /** Must match `AssetWatchAppSource` in `:plugins:sync` */
        const val ASSET_DIR = "watch_app"
        /** Not ".gz": aapt unpacks such an asset and drops the extension, so the APK would be stored at full size */
        const val APK_ASSET = "wear.apk.gzip"
        const val INFO_ASSET = "watch_app.properties"

        /** The gradle property that names the watch APK */
        const val PROPERTY = "embedWearApk"

        /**
         * Registers the task for every variant of [project] when the build was started with
         * `-PembedWearApk=<file>`. Does nothing without the property.
         */
        fun register(project: Project) {
            val apkPath = project.findProperty(PROPERTY)?.toString()?.takeIf { it.isNotBlank() } ?: return
            val apk = project.file(apkPath)
            if (!apk.isFile) throw GradleException("-P$PROPERTY: $apk does not exist")
            val components = project.extensions.getByType(ApplicationAndroidComponentsExtension::class.java)
            val aapt2 = components.sdkComponents.sdkDirectory.map { sdk ->
                // The newest build tools that have aapt2
                val buildTools = sdk.dir("build-tools").asFile.listFiles().orEmpty()
                    .filter { File(it, "aapt2").isFile || File(it, "aapt2.exe").isFile }
                    .maxByOrNull { it.name }
                    ?: throw GradleException("No aapt2 in ${sdk.asFile}/build-tools")
                sdk.file("build-tools/${buildTools.name}/" + if (File(buildTools, "aapt2").isFile) "aapt2" else "aapt2.exe")
            }
            components.onVariants { variant ->
                val name = variant.name.replaceFirstChar { it.uppercase() }
                val task = project.tasks.register("embed${name}WatchApp", EmbedWatchAppTask::class.java) {
                    watchApk.set(apk)
                    this.aapt2.set(aapt2)
                    applicationId.set(variant.applicationId)
                }
                variant.sources.assets?.addGeneratedSourceDirectory(task, EmbedWatchAppTask::outputDir)
            }
        }
    }
}
