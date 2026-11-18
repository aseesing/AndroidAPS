package app.aaps.wear.phonepreview

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.CwfWatchSettings
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.wear.R
import app.aaps.wear.complications.cwf.CwfFramePipeline
import app.aaps.wear.watchfaces.CustomWatchface
import kotlinx.coroutines.runBlocking

/**
 * Draws the Custom watch face on the phone, with the same code the watch uses.
 *
 * A frame goes through the watch's own [CwfFramePipeline], the path the watch uses to draw the face
 * into a picture. So the clock, the hands and the values are drawn as on the wrist. The text views
 * place their lines as on the watch, which follows older Android rules, see [WatchLineSpacing].
 *
 * The values come in as the same [EventData] messages the phone sends to the watch, see [apply].
 *
 * Not thread safe: call everything from one thread, a [PreviewThread]. Drawing works on views, so it
 * is not done on the main thread, to keep the phone's screen smooth.
 *
 * Only one instance may exist in a process, see [PreviewHost].
 */
class CwfPreviewRenderer(context: Context, private val logger: AAPSLogger) {

    /** Kept for the whole run: the face's storage allows one owner per file, see [PreviewHost]. */
    private val host = PreviewHost(context, logger)

    /**
     * The part that draws, for frames of [size] x [size] pixels: the face with its views and the kept
     * layer pictures. Made when a frame is asked for, and dropped again by [release]. The values, the
     * design and the settings are kept by [host], so a new one draws the same face.
     *
     * One size only: the face fixes the scale of its views to the size of its first frame, as a watch
     * has one screen size. A frame of another size gets a new [Drawing].
     */
    private inner class Drawing(val size: Int) {

        val face = CustomWatchface().apply { prepareForRendering(host) }
        val pipeline = CwfFramePipeline(host, logger) { face }

        /** When the face last read the values, or may have, in wall clock ms. `prepareForRendering` reads them. */
        var valuesReadAt = System.currentTimeMillis()

        /** The minute of the last frame. A new minute reloads the fields, as `onTimeChanged` does on the watch. */
        var minute = -1L
    }

    private var drawing: Drawing? = null

    /** The [Drawing] for frames of [size]. A new one when the size changed: the screen turned, or another screen shows the preview. */
    private fun drawing(size: Int): Drawing {
        drawing?.takeIf { it.size == size }?.let { return it }
        release()
        return Drawing(size).also { drawing = it }
    }

    /** True when new values came in that the face has not read yet. */
    private var newValues = false

    /** True when the next frame reads the values for another reason: a new design, new settings or a new minute. */
    private var readsValues = false

    /** The design last set, to skip setting the same design again. */
    private var design: CwfData? = null

    /** The watch settings this preview draws with. Call [settingsChanged] after writing them. */
    val watchSettings: SharedPreferences get() = host.watchSettings

    /** Shows [cwfData] from the next frame on. */
    fun setDesign(cwfData: CwfData) {
        if (cwfData == design) return
        design = cwfData
        runBlocking { host.repository.storeCustomWatchface(cwfData) }
        drawing?.pipeline?.invalidateAll()
        readsValues = true
    }

    /**
     * True when [cwfData] is the default design of the watch: the one the watch face code makes itself,
     * with no zip behind it. Then the copy the watch sends is all there is, and it can be drawn as it is.
     */
    fun isDefaultDesign(cwfData: CwfData): Boolean {
        // The face stores its default design when it builds its views the first time
        val drawing = drawing ?: drawing(DEFAULT_SIZE)
        drawing.face.prepareLayout(drawing.size, drawing.size)
        val default = runBlocking { host.repository.getCustomWatchface(isDefault = true) } ?: return false
        return listOf(CwfMetadataKey.CWF_NAME, CwfMetadataKey.CWF_AUTHOR, CwfMetadataKey.CWF_AUTHOR_VERSION)
            .all { key -> cwfData.metadata[key] != null && cwfData.metadata[key] == default.metadata[key] }
    }

    /** Takes a message the phone sends to the watch, as the watch does. Others are left out. */
    fun apply(event: EventData) {
        val repository = host.repository
        when (event) {
            is EventData.SingleBg      -> runBlocking { repository.updateBgData(event) }
            is EventData.Status        -> runBlocking { repository.updateStatusData(event) }
            is EventData.GraphData     -> runBlocking { repository.updateGraphData(event) }
            is EventData.TreatmentData -> runBlocking { repository.updateTreatmentData(event) }
            is EventData.Preferences   -> host.sp.putBoolean(R.string.key_units_mgdl, event.unitsMgdl)
            else                       -> return
        }
        newValues = true
    }

    /** Draws again with the [watchSettings] as they are now. */
    fun settingsChanged() {
        drawing?.pipeline?.invalidateAll()
        readsValues = true
    }

    /** The face at [nowMs], [size] pixels wide and high. */
    fun frame(size: Int, nowMs: Long = System.currentTimeMillis()): Bitmap {
        val drawing = drawing(size)
        // The hour, minute and date fields are filled with the data, from the real clock
        val minute = nowMs / MS_PER_MINUTE
        if (minute != drawing.minute) {
            drawing.minute = minute
            drawing.pipeline.invalidateData()
            readsValues = true
        }
        // The face reads the values again only when its last read is older than half a second (so the
        // halves of one picture of the watch agree, see `BaseWatchFace.refreshRenderData`). New values
        // are kept until a frame that surely reads them, or they would be skipped until the next minute.
        val wallClock = System.currentTimeMillis()
        if (newValues && wallClock - drawing.valuesReadAt >= VALUES_READ_GAP_MS) {
            newValues = false
            drawing.pipeline.invalidateData()
            readsValues = true
        }
        var frame = drawing.pipeline.compose(nowMs, ambient = false, width = size, height = size)
        // Lines placed as on the watch. When a view changed (a new design, a text that got a line
        // more or less), its text lies somewhere else: draw the frame again. That frame rebuilds all
        // layers and may read the values, so it counts as a read
        if (drawing.face.layoutView?.let(WatchLineSpacing::match) == true) {
            frame.recycle()
            drawing.pipeline.invalidateAll()
            frame = drawing.pipeline.compose(nowMs, ambient = false, width = size, height = size)
            readsValues = true
        }
        if (readsValues) {
            readsValues = false
            // The time after the frame, not before it: the face read somewhere inside it, and a slow
            // frame (the first one of a new face) must not make the next read look allowed too early
            drawing.valuesReadAt = System.currentTimeMillis()
        }
        return frame
    }

    /**
     * Frees the kept layer pictures and drops this renderer's face while no preview is shown. The
     * values, the design and the settings stay. The next [frame] builds the face again.
     *
     * The last face is not fully freed: the watch code keeps it in static fields (the style owner of
     * `CustomWatchface` and the fields of its `ViewMap` entries), and with it its views and the
     * pictures of the design. That is one face at most, until the next face takes its place.
     */
    fun release() {
        drawing?.pipeline?.release()
        drawing = null
    }

    /**
     * Writes the watch settings [values] (stored name -> value, as in `EventData.CwfSettingsSync`) into
     * [watchSettings], and draws with them from the next frame on. Settings the phone may not set and
     * values they can not have are left out, see [CwfWatchSettings].
     *
     * @return true when a setting changed
     */
    fun applyWatchSettings(values: Map<String, String>): Boolean {
        val changes = values.filter { (name, value) ->
            val setting = CwfWatchSettings.of(name)
            setting != null && CwfWatchSettings.isAllowed(name, value) && watchSettings.getBoolean(name, setting.default).toString() != value
        }
        if (changes.isEmpty()) return false
        watchSettings.edit().apply { changes.forEach { (name, value) -> putBoolean(name, value.toBooleanStrict()) } }.commit()
        settingsChanged()
        return true
    }

    private companion object {

        const val MS_PER_MINUTE = 60_000L

        /** More than the half second in which the face does not read the values again. */
        const val VALUES_READ_GAP_MS = 1_000L

        /** The size of the views built only to learn the default design. Any size gives the same design. */
        const val DEFAULT_SIZE = 400
    }
}
