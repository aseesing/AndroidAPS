// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
//
// The Custom watch face renderer of the watch app, built a second time for the phone, so the phone
// can show a live preview that is drawn by exactly the same code as the watch.
//
// Nothing is moved and nothing in `wear` is edited. A sync task copies the listed watch sources, and
// only the resources they really use, into this module's build folder. Watch-only classes that the
// sources name but never run on the phone get a small stand-in in `src/main`.
//
// Only the resources the renderer uses are copied, never all of them: `wear` has many names that the
// phone modules use too (`error`, `confirm`, `carbs`, ...). Merged into the phone app, one would
// silently replace the other.

import org.w3c.dom.Element
import javax.xml.parsers.DocumentBuilderFactory
import javax.xml.transform.OutputKeys
import javax.xml.transform.TransformerFactory
import javax.xml.transform.dom.DOMSource
import javax.xml.transform.stream.StreamResult

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.compose.compiler)
    kotlin("plugin.serialization")
    alias(libs.plugins.metro)
    id("android-module-dependencies")
    id("test-module-dependencies")
}

val wearRoot: File = rootProject.file("wear/src/main")
val wearSrc: File = File(wearRoot, "kotlin/app/aaps/wear")
val wearResDir: File = File(wearRoot, "res")

/** The watch sources the renderer needs. A file missing here fails to compile, it never fails silently. */
val wearShared = listOf(
    "complications/cwf/CwfCachePolicy.kt",
    "complications/cwf/CwfFramePipeline.kt",
    "complications/cwf/CwfRenderTarget.kt",
    "data/ComplicationDataRepository.kt",
    "data/ComplicationStore.kt",
    "data/LiveComplicationData.kt",
    "events/EventWearPreferenceChange.kt",
    "health/HealthValues.kt",
    "utils/ViewExtensions.kt",
    "watchdirect/WatchDirectReadings.kt",
    "watchdirect/WatchSensorLink.kt",
    "watchfaces/CustomSlotDefaults.kt",
    "watchfaces/CustomWatchface.kt",
    "watchfaces/animation/GlitchField.kt",
    "watchfaces/animation/HeartPulse.kt",
    "watchfaces/animation/PulseCurve.kt",
    "watchfaces/animation/RainField.kt",
    "watchfaces/animation/WakeAnimationSpec.kt",
    "watchfaces/animation/WakeAnimator.kt",
    "watchfaces/animation/WakeFrameLayers.kt",
    "watchfaces/animation/WakeLength.kt",
    "watchfaces/animation/WakeTimer.kt",
    "watchfaces/daynight/DayNightChoice.kt",
    "watchfaces/daynight/DayNightClock.kt",
    "watchfaces/daynight/DayOverlay.kt",
    "watchfaces/daynight/MoonPhase.kt",
    "watchfaces/daynight/NightFace.kt",
    "watchfaces/daynight/Orbit.kt",
    "watchfaces/daynight/SunTimes.kt",
    "watchfaces/settingsync/CwfSyncedSettings.kt",
    "watchfaces/utils/BaseWatchFace.kt",
    "watchfaces/utils/BgGraphBuilder.kt",
    "watchfaces/utils/FirstFrameAfterWake.kt",
    "watchfaces/utils/LayeredFrame.kt",
    "watchfaces/utils/ParsedJsonCache.kt",
    "watchfaces/utils/SecondVisibility.kt",
    "watchfaces/utils/SimpleUi.kt",
    "watchfaces/utils/WatchFace.kt",
    "watchfaces/utils/WatchFaceComplication.kt",
    "watchfaces/utils/WatchFaceSettings.kt",
    "watchfaces/utils/WatchFaceTime.kt",
    "watchfaces/utils/WatchMode.kt",
    "watchfaces/utils/WatchShape.kt",
    "watchfaces/utils/WatchfaceViewAdapter.kt",
)

val sharedKotlinDir = layout.buildDirectory.dir("generated/wearShared/kotlin")
val sharedResDir = layout.buildDirectory.dir("generated/wearShared/res")

val syncWearSources = tasks.register<Sync>("syncWearSources") {
    from(wearSrc) { wearShared.forEach { include(it) } }
    into(sharedKotlinDir.map { it.dir("app/aaps/wear") })
}

/**
 * Copies the resources of `wear` that the code of this module refers to, and everything those refer to
 * in turn. File resources (layouts, drawables, fonts) are copied as files, with every qualifier.
 * Values (strings, colours, dimensions, styles) are copied entry by entry into one file per qualifier,
 * translations included.
 */
abstract class SyncWearResources : DefaultTask() {

    @get:InputFiles abstract val kotlinSources: ConfigurableFileCollection
    @get:InputDirectory abstract val wearRes: DirectoryProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    private val fileTypes = setOf("layout", "drawable", "font", "color", "xml", "mipmap", "anim", "raw")
    private val valueTypeOfTag = mapOf(
        "string" to "string", "color" to "color", "dimen" to "dimen", "bool" to "bool", "integer" to "integer",
        "string-array" to "array", "integer-array" to "array", "array" to "array", "plurals" to "plurals",
        "style" to "style", "attr" to "attr", "declare-styleable" to "styleable", "fraction" to "fraction"
    )
    private val codeRef = Regex("""\bR\.(\w+)\.(\w+)""")
    private val bindingRef = Regex("""\bapp\.aaps\.wear\.databinding\.(\w+)Binding\b""")
    private val xmlRef = Regex("""(?<![\w:])[@?](?:\+)?(?:attr/)?(\w+)/([\w.]+)""")

    @TaskAction
    fun run() {
        val res = wearRes.get().asFile
        val out = outputDir.get().asFile.apply { deleteRecursively(); mkdirs() }
        val needed = HashSet<Pair<String, String>>()
        kotlinSources.asFileTree.filter { it.extension == "kt" }.forEach { file ->
            val text = file.readText()
            codeRef.findAll(text).forEach { needed += it.groupValues[1] to it.groupValues[2] }
            bindingRef.findAll(text).forEach { m ->
                needed += "layout" to m.groupValues[1].replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()
            }
        }
        // Styleable entries are `Name_attr` in code; the resource is `Name`
        needed.filter { it.first == "styleable" }.forEach { needed += "styleable" to it.second.substringBefore('_') }

        val factory = DocumentBuilderFactory.newInstance()
        val valueFiles = res.listFiles { f -> f.isDirectory && f.name.startsWith("values") }.orEmpty()
            .flatMap { dir -> dir.listFiles { f -> f.extension == "xml" }.orEmpty().map { dir.name to it } }
        val values = valueFiles.flatMap { (dir, file) ->
            val root = factory.newDocumentBuilder().parse(file).documentElement
            (0 until root.childNodes.length).map { root.childNodes.item(it) }.filterIsInstance<Element>().mapNotNull { e ->
                val type = if (e.tagName == "item") e.getAttribute("type") else valueTypeOfTag[e.tagName]
                type?.takeIf { it.isNotEmpty() }?.let { Triple(dir, it to e.getAttribute("name"), e) }
            }
        }
        fun key(type: String, name: String) = type to name.replace('.', '_')

        val copied = HashSet<File>()
        val kept = LinkedHashSet<Triple<String, Pair<String, String>, Element>>()
        var before = -1
        while (needed.size != before) {
            before = needed.size
            // File resources
            res.listFiles { f -> f.isDirectory && f.name.substringBefore('-') in fileTypes }.orEmpty().forEach { dir ->
                val type = dir.name.substringBefore('-')
                dir.listFiles().orEmpty().filter { (type to it.name.substringBefore('.')) in needed && it !in copied }.forEach { file ->
                    copied += file
                    if (file.extension == "xml") scan(file.readText(), needed)
                }
            }
            // Value resources
            values.filter { (_, k, _) -> key(k.first, k.second) in needed }.forEach { entry ->
                if (kept.add(entry)) {
                    val e = entry.third
                    scan(e.textContent + " " + attributesOf(e), needed)
                    if (e.tagName == "style") {
                        val parent = e.getAttribute("parent").removePrefix("@style/")
                        if (parent.isNotEmpty() && !parent.startsWith("@android") && !parent.startsWith("android:")) needed += key("style", parent)
                        else if (parent.isEmpty() && e.getAttribute("name").contains('.')) needed += key("style", e.getAttribute("name").substringBeforeLast('.'))
                    }
                }
            }
        }

        copied.forEach { file -> file.copyTo(File(out, file.parentFile.name + "/" + file.name), overwrite = true) }
        kept.groupBy { it.first }.forEach { (dir, entries) ->
            val doc = factory.newDocumentBuilder().newDocument()
            val root = doc.createElement("resources").also { doc.appendChild(it) }
            root.setAttribute("xmlns:tools", "http://schemas.android.com/tools")
            entries.forEach { root.appendChild(doc.importNode(it.third, true)) }
            val target = File(out, "$dir/wear_shared_values.xml").apply { parentFile.mkdirs() }
            TransformerFactory.newInstance().newTransformer().apply { setOutputProperty(OutputKeys.INDENT, "yes") }
                .transform(DOMSource(doc), StreamResult(target))
        }
    }

    private fun attributesOf(e: Element): String = buildString {
        val all = e.getElementsByTagName("*")
        for (i in 0 until e.attributes.length) append(e.attributes.item(i).nodeValue).append(' ')
        for (n in 0 until all.length) (all.item(n) as Element).let { c ->
            for (i in 0 until c.attributes.length) append(c.attributes.item(i).nodeValue).append(' ')
        }
    }

    private fun scan(text: String, needed: MutableSet<Pair<String, String>>) {
        xmlRef.findAll(text).forEach { m ->
            val type = m.groupValues[1]
            if (type != "android" && type != "id") needed += type to m.groupValues[2].replace('.', '_')
        }
    }
}

val syncWearResources = tasks.register<SyncWearResources>("syncWearResources") {
    dependsOn(syncWearSources)
    kotlinSources.from(sharedKotlinDir, file("src/main/kotlin"))
    wearRes.set(wearResDir)
    outputDir.set(sharedResDir)
}

android {
    namespace = "app.aaps.wear"
    sourceSets.getByName("main") {
        kotlin.directories.add(sharedKotlinDir.get().asFile.path)
        res.directories.add(sharedResDir.get().asFile.path)
    }
}

/**
 * The chart library of the watch comes as a local `.aar` that holds only code. A library module may
 * not depend on a local `.aar`, so its `classes.jar` is unpacked and used as a plain jar.
 */
val chartJar = layout.buildDirectory.file("generated/wearShared/libs/hellocharts-library-1.5.8.jar")
val unpackChartJar = tasks.register<Copy>("unpackChartJar") {
    from(zipTree(rootProject.file("wear/libs/hellocharts-library-1.5.8.aar"))) {
        include("classes.jar")
        rename { "hellocharts-library-1.5.8.jar" }
    }
    into(chartJar.map { it.asFile.parentFile })
}

tasks.named("preBuild") { dependsOn(syncWearSources, syncWearResources, unpackChartJar) }

dependencies {
    implementation(project(":shared:impl"))
    implementation(project(":core:interfaces"))
    implementation(project(":core:keys"))
    implementation(project(":core:data"))

    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.wear)
    implementation(libs.androidx.wear.watchface)
    implementation(libs.androidx.wear.watchface.complications.data)
    implementation(libs.androidx.wear.watchface.complications.datasource)
    implementation(libs.androidx.wear.watchface.complications.datasource.ktx)
    implementation(libs.androidx.wear.watchface.complications)
    implementation(libs.androidx.wear.watchface.complications.rendering)
    implementation(libs.androidx.wear.watchface.editor)
    implementation(libs.androidx.wear.watchface.client)
    implementation(libs.androidx.health.services.client)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.protobuf)
    implementation(files(chartJar).builtBy(unpackChartJar))

    testImplementation(libs.org.robolectric)
    testRuntimeOnly(libs.org.junit.vintage.engine)
}
