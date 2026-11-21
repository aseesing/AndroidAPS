package app.aaps.wear.watchfaces.wff

import android.content.Context
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.shared.impl.weardata.ZipWatchfaceFormat
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipFile

/**
 * The Watch Face Format face that the wearer loaded on the phone from a face zip (see
 * `_docs/WFF_FACE_ZIP.md`). The phone sends a small zip with only what this watch needs:
 *
 * - [FACE_JSON]: the name of the face
 * - [DATA_ZIP]: a design zip with the parts the watch draws into the data picture, and the arrows
 * - [APK]: the face, in the Watch Face Format version this watch can run
 * - [TOKEN]: the Watch Face Push token of the APK, only on Wear OS 6 and newer
 *
 * The zip is kept as it came, in one file, so the face can be installed again later: after the
 * wearer chose another face on the phone and then this one again.
 */
@SingleIn(AppScope::class)
@Inject
class WffFace(private val context: Context, private val aapsLogger: AAPSLogger) {

    private val file: File get() = File(context.filesDir, FILE_NAME)

    /** The data design read from [file], and the time of the file it was read from. */
    private var cachedDesign: CwfData? = null
    private var cachedFrom = 0L

    /** Keeps [zip] from the phone in place of the face before. */
    @Synchronized
    fun store(zip: ByteArray) {
        val next = File(context.filesDir, "$FILE_NAME.new")
        next.writeBytes(zip)
        check(next.renameTo(file)) { "the face could not be stored" }
        // The file time can stay the same within a second: read the design again for sure
        cachedFrom = -1L
    }

    // PERSONAL PATCH: whether the watch shows the face, see WffFaceShown.kt and WffShownSlots.kt
    private val shownSlots = WffShownSlots(context)

    /**
     * Whether the face is on the screen of the watch: true while the system uses one of the face's
     * complication sources, false when it uses none or a code-based face of this app is on the
     * screen. Null when the system never said.
     */
    val shown: Boolean? get() = shownSlots.shown

    /** The system started to use the complication [slot] of the face. */
    fun slotActivated(slot: String) {
        shownSlots.activated(slot)
    }

    /** The system stopped using the complication [slot] of the face. */
    fun slotDeactivated(slot: String) {
        shownSlots.deactivated(slot)
    }

    /** The name of the face, or null when there is none. */
    val name: String? get() = entry(FACE_JSON)?.let { JSONObject(String(it)).optString("name").ifEmpty { null } }

    /** The Watch Face Push token of [apk], or null when there is none. */
    val token: String? get() = entry(TOKEN)?.let { String(it).trim() }

    /** The face APK, or null when there is none. */
    fun apk(): ByteArray? = entry(APK)

    /**
     * The design that the data picture and the arrows of the face come from, or null when there is
     * none. It never changes the watch settings: it is loaded without the authorization to do that.
     */
    @Synchronized
    fun dataDesign(): CwfData? {
        val time = file.lastModified()
        if (time != cachedFrom) {
            cachedFrom = time
            cachedDesign = entry(DATA_ZIP)?.let { ZipWatchfaceFormat.loadCustomWatchface(it, DATA_ZIP, authorization = false)?.cwfData }
        }
        return cachedDesign
    }

    private fun entry(name: String): ByteArray? =
        try {
            if (!file.exists()) null
            else ZipFile(file).use { zip -> zip.getEntry(name)?.let { entry -> zip.getInputStream(entry).use { it.readBytes() } } }
        } catch (e: Exception) {
            aapsLogger.error(LTag.WEAR, "WffFace: $name not read", e)
            null
        }

    companion object {

        const val FACE_JSON = "face.json"
        const val DATA_ZIP = "data.zip"
        const val APK = "face.apk"
        const val TOKEN = "face.token"
        private const val FILE_NAME = "wff_face.zip"
    }
}
