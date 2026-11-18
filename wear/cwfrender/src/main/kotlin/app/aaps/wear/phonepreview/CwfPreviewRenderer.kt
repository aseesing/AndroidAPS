// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
// The personal renderer, larger than the upstream one (issue 5610): it plays the wake-up animation,
// draws the complication slots, takes the weather, and has a storage per face (day and night). The
// reviewed fixes of upstream are ported into it: a new face per size, new values kept until a frame
// that surely reads them, and the default design of the watch drawn without a zip.
package app.aaps.wear.phonepreview

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.core.graphics.createBitmap
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.CwfMetadataKey
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.toDrawable
import app.aaps.wear.R
import app.aaps.wear.complications.cwf.CwfFramePipeline
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.watchfaces.animation.WakeAnimator
import app.aaps.wear.watchfaces.animation.WakeLength
import app.aaps.wear.watchfaces.daynight.DayNightChoice
import app.aaps.wear.watchfaces.daynight.StoredWeather
import app.aaps.wear.watchfaces.settingsync.CwfSettingNames
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Draws the Custom watch face on the phone, with the same code the watch uses.
 *
 * - A still frame goes through the watch's own [CwfFramePipeline], the path the watch uses to draw
 *   the face into a picture. So the clock, the hands and the values are drawn as on the wrist.
 * - The wake-up animation is drawn by the watch's own [WakeAnimator], over the views of the face.
 *   It plays when a design is set, as on the watch when the face starts, and again after [wake].
 *
 * The values come in as the same [EventData] messages the phone sends to the watch, see [apply].
 *
 * Not thread safe: call everything from one thread. Drawing works on views, so it is not done on
 * the main thread, to keep the phone's screen smooth.
 *
 * Only one instance per name may exist in a process, see [PreviewHost].
 */
class CwfPreviewRenderer(context: Context, private val logger: AAPSLogger, name: String = PreviewHost.DEFAULT_NAME) {

    /** Kept for the whole run: the face's storage allows one owner per file, see [PreviewHost]. */
    private val host = PreviewHost(context, logger, name)

    /**
     * The part that draws, for frames of [size] x [size] pixels: the face with its views, the kept
     * layer pictures and the animation. Made when a frame is asked for, and dropped again by [release].
     * The values, the design and the settings are kept by [host], so a new one draws the same face.
     *
     * One size only: the face fixes the scale of its views to the size of its first frame, as a watch
     * has one screen size. A frame of another size gets a new [Drawing], and its animation plays, as
     * when the preview is shown again.
     */
    private inner class Drawing(val size: Int) {

        /** The time of the frame being drawn, for the complication slots. */
        var frameTime = System.currentTimeMillis()

        private val slots = PreviewSlots(host, host.healthValues, logger)

        val face = CustomWatchface().apply {
            prepareForRendering(host)
            // The system draws the slots on the watch; here the preview draws them itself
            slotPainter = { canvas -> slots.draw(canvas, this, frameTime) }
        }
        val pipeline = CwfFramePipeline(host, logger) { face }
        val wakeAnimator = WakeAnimator(
            heartRate = { null }, // the phone does not measure it; the pulse uses the design's fallback
            chosenLengthMs = { WakeLength.parse(host.sp.getString(R.string.key_wake_animation_length, WakeLength.AS_DESIGN)) },
            value = { key -> face.wakeValue(key) }, // a low battery blinks, as on the watch
            moon = { epochMs, size -> face.wakeMoon(epochMs, size) } // the moon layers, the same picture as the face's resting layers
        )

        /** When the face last read the values, or may have, in wall clock ms. `prepareForRendering` reads them. */
        var valuesReadAt = System.currentTimeMillis()

        /** The minute of the last frame. A new minute reloads the fields, as `onTimeChanged` does on the watch. */
        var minute = -1L

        /** Takes the animation of [cwfData]. It plays once, as on the watch when the face starts. */
        fun useDesign(cwfData: CwfData) {
            wakeAnimator.setDesign(face.readWakeAnimation(JSONObject(cwfData.json)) { key -> cwfData.resData[key]?.toDrawable(host.resources) })
            pipeline.invalidateAll()
        }

        fun release() {
            pipeline.release()
            wakeAnimator.release()
        }
    }

    private var drawing: Drawing? = null

    /** The [Drawing] for frames of [size]. A new one when the size changed: the screen turned, or another screen shows the preview. */
    private fun drawing(size: Int): Drawing {
        drawing?.takeIf { it.size == size }?.let { return it }
        release()
        return Drawing(size).also { new ->
            drawing = new
            design?.let(new::useDesign)
        }
    }

    /** True when new values came in that the face has not read yet. */
    private var newValues = false

    /** True when the next frame reads the values for another reason: a new design, new settings or a new minute. */
    private var readsValues = false

    /** Changes each time the values change, so the animation knows its kept pictures are old. */
    private var dataVersion = 0

    /** The design last set, to skip setting the same design again. */
    private var design: CwfData? = null

    /** The watch settings this preview draws with. Call [settingsChanged] after writing them. */
    val watchSettings: SharedPreferences get() = host.watchSettings

    /** True while the wake-up animation plays. Then frames are wanted every [frameDelayMs]. */
    val isAnimating: Boolean get() = drawing?.wakeAnimator?.isRunning == true

    /** How long to wait for the next frame: short while the animation plays, else [normalMs]. */
    fun frameDelayMs(normalMs: Long): Long = drawing?.wakeAnimator?.updateDelay(normalMs) ?: normalMs

    /** Shows [cwfData] from the next frame on. The animation of the design plays once. */
    fun setDesign(cwfData: CwfData) {
        if (cwfData == design) return
        design = cwfData
        runBlocking { host.repository.storeCustomWatchface(cwfData) }
        drawing?.useDesign(cwfData)
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
        val sp = host.sp
        when (event) {
            is EventData.SingleBg      -> runBlocking { repository.updateBgData(event) }
            is EventData.Status        -> runBlocking { repository.updateStatusData(event) }
            is EventData.GraphData     -> runBlocking { repository.updateGraphData(event) }
            is EventData.TreatmentData -> runBlocking { repository.updateTreatmentData(event) }

            is EventData.Weather       -> StoredWeather.save(sp, event)

            is EventData.Preferences   -> {
                sp.putBoolean(R.string.key_units_mgdl, event.unitsMgdl)
                val latitude = event.latitude
                val longitude = event.longitude
                if (latitude != null && longitude != null) {
                    sp.putDouble(R.string.key_location_latitude, latitude)
                    sp.putDouble(R.string.key_location_longitude, longitude)
                }
                DayNightChoice.fromPhone(event.dayNightLook, "", DayNightChoice.ACTIVE_VALUES)?.let { sp.putString(R.string.key_day_night_look, it) }
                DayNightChoice.fromPhone(event.alwaysOnLook, "", DayNightChoice.ALWAYS_ON_VALUES)?.let { sp.putString(R.string.key_day_night_always_on, it) }
            }

            else                       -> return
        }
        // Read by the next frame that surely reads the values, see [frame]
        newValues = true
    }

    /** Draws again with the [watchSettings] as they are now. */
    fun settingsChanged() {
        dataVersion++
        drawing?.pipeline?.invalidateAll()
        readsValues = true
    }

    /** Plays the wake-up animation again, as a tap on the watch does. False when there is none to play. */
    fun wake(): Boolean = drawing?.wakeAnimator?.wakeOnTap() == true

    /** The face at [nowMs], [size] pixels wide and high: a frame of the animation while it plays. */
    fun frame(size: Int, nowMs: Long = System.currentTimeMillis()): Bitmap {
        val drawing = drawing(size)
        drawing.frameTime = nowMs
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
            dataVersion++ // the kept pictures of the animation show the old values
            drawing.pipeline.invalidateData()
            readsValues = true
        }
        // Also when the animation plays: this sets the clock and the values in the views it draws over
        val still = drawing.pipeline.compose(nowMs, ambient = false, width = size, height = size)
        if (readsValues) {
            readsValues = false
            // The time after the frame, not before it: the face read somewhere inside it, and a slow
            // frame (the first one of a new face) must not make the next read look allowed too early
            drawing.valuesReadAt = System.currentTimeMillis()
        }
        val wantsAnimation = frameDelayMs(Long.MAX_VALUE) != Long.MAX_VALUE
        if (!wantsAnimation) return still
        val animated = createBitmap(size, size)
        val content = drawing.face.styleId * 31 + dataVersion
        val drawn = drawing.wakeAnimator.drawFrame(
            Canvas(animated), size, size, nowMs, content, emptyList<Any?>(), drawing.face.wakeParts, allowed = true, ready = true
        )
        return if (drawn) {
            still.recycle()
            animated
        } else {
            animated.recycle()
            still
        }
    }

    /**
     * Frees the face, its views and its pictures while no preview is shown. The values, the design
     * and the settings stay. The next [frame] builds the face again, and the animation plays again,
     * as on the watch when the face becomes visible.
     *
     * The last face is not fully freed: the watch code keeps it in static fields (the style owner of
     * `CustomWatchface` and the fields of its `ViewMap` entries), and with it its views and the
     * pictures of the design. That is one face at most, until the next face takes its place.
     */
    fun release() {
        drawing?.release()
        drawing = null
    }

    /** The watch settings the phone can set, by their stored names. */
    private val settingNames: CwfSettingNames by lazy { CwfSettingNames(host.resources) }

    /**
     * Writes the watch settings [values] (stored name -> value, as in `EventData.CwfSettingsSync`) into
     * [watchSettings], and draws with them from the next frame on. Unknown names and values are left out.
     *
     * @return true when the length of the wake-up animation changed, so the caller can play it again
     */
    fun applyWatchSettings(values: Map<String, String>): Boolean {
        var changed = false
        var wakeChanged = false
        values.forEach { (name, value) ->
            val setting = settingNames.settings[name] ?: return@forEach
            if (!settingNames.isAllowed(name, value) || settingNames.read(host.sp, setting) == value) return@forEach
            settingNames.write(host.sp, setting, value)
            changed = true
            if (setting.key == R.string.key_wake_animation_length) wakeChanged = true
        }
        if (changed) settingsChanged()
        return wakeChanged
    }

    private companion object {

        const val MS_PER_MINUTE = 60_000L

        /** More than the half second in which the face does not read the values again. */
        const val VALUES_READ_GAP_MS = 1_000L

        /** The size of the views built only to learn the default design. Any size gives the same design. */
        const val DEFAULT_SIZE = 400
    }
}
