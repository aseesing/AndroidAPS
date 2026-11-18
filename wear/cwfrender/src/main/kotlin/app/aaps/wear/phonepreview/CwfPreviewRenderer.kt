// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.phonepreview

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import androidx.core.graphics.createBitmap
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.weardata.CwfData
import app.aaps.core.interfaces.rx.weardata.EventData
import app.aaps.shared.impl.weardata.toDrawable
import app.aaps.wear.R
import app.aaps.wear.complications.cwf.CwfFramePipeline
import app.aaps.wear.health.HealthValues
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
 * A frame is drawn into one of [BUFFERS] bitmaps kept here, in turn (see [frame]), so a running
 * animation makes no new bitmap per frame. The caller says which of them it may still show, and those
 * are never drawn into.
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
     * The part that draws: the face with its views, the kept layer pictures and the animation. Made
     * when a frame is asked for, and dropped again by [release]. The values, the design and the
     * settings are kept by [host], so a new one draws the same face.
     */
    private inner class Drawing {

        /** The time of the frame being drawn, for the complication slots. */
        var frameTime = System.currentTimeMillis()

        private val slots = PreviewSlots(host, HealthValues.of(host), logger)

        val face = CustomWatchface().apply {
            prepareForRendering(host)
            // The system draws the slots on the watch; here the preview draws them itself
            slotPainter = { canvas -> slots.draw(canvas, this, frameTime) }
        }
        val pipeline = CwfFramePipeline(host, logger) { face }
        val wakeAnimator = WakeAnimator(
            heartRate = { null }, // the phone does not measure it; the pulse uses the design's fallback
            chosenLengthMs = { WakeLength.parse(host.sp.getString(R.string.key_wake_animation_length, WakeLength.AS_DESIGN)) },
            value = { key -> face.designValue(key) }, // a low battery blinks, as on the watch
            images = { name, epochMs, size -> face.dynamicImage(name, epochMs, size) } // the moon layers, the same picture as the face's resting layers
        )

        /** The size of the last frame. The kept layer pictures only fit that size. */
        var size = 0

        /** The minute of the last frame. A new minute reloads the fields, as `onTimeChanged` does on the watch. */
        var minute = -1L

        /**
         * True when the views do not show the values, the design or the size of now: the next frame
         * goes through [pipeline], which reloads them. Else a frame of the animation only sets the clock.
         */
        var stale = true

        /** Takes the animation of [cwfData]. It plays once, as on the watch when the face starts. */
        fun useDesign(cwfData: CwfData) {
            wakeAnimator.setDesign(face.readWakeAnimation(JSONObject(cwfData.json)) { key -> cwfData.resData[key]?.toDrawable(host.resources) })
            invalidate(all = true)
        }

        /** Marks the views as out of date: [all] also for a new design or size, else for new values. */
        fun invalidate(all: Boolean) {
            if (all) pipeline.invalidateAll() else pipeline.invalidateData()
            stale = true
        }

        /** One canvas per buffer of [buffers], by its index, with the bitmap it draws on. */
        private val canvases = arrayOfNulls<Canvas>(BUFFERS)
        private val canvasBitmaps = arrayOfNulls<Bitmap>(BUFFERS)

        fun canvasFor(index: Int): Canvas {
            val bitmap = buffers[index]!!
            canvases[index]?.let { if (canvasBitmaps[index] === bitmap) return it }
            return Canvas(bitmap).also {
                canvases[index] = it
                canvasBitmaps[index] = bitmap
            }
        }

        fun release() {
            pipeline.release()
            wakeAnimator.release()
            canvases.fill(null)
            canvasBitmaps.fill(null)
        }
    }

    private var drawing: Drawing? = null

    private fun drawing(): Drawing = drawing ?: Drawing().also { new ->
        drawing = new
        design?.let(new::useDesign)
    }

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
        dataVersion++
        drawing?.invalidate(all = false)
    }

    /** Draws again with the [watchSettings] as they are now. */
    fun settingsChanged() {
        dataVersion++
        drawing?.invalidate(all = true)
    }

    /** Plays the wake-up animation again, as a tap on the watch does. False when there is none to play. */
    fun wake(): Boolean = drawing?.wakeAnimator?.wakeOnTap() == true

    /** The bitmaps the frames are drawn into, in turn, and the one the next frame tries first. */
    private val buffers = arrayOfNulls<Bitmap>(BUFFERS)
    private var nextBuffer = 0

    /** Copies a still frame exactly, also where it is see-through. */
    private val copyPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    /**
     * The face at [nowMs], [size] pixels wide and high: a frame of the animation while it plays.
     *
     * The bitmap belongs to this renderer and is drawn into again a few frames later. So a caller must
     * copy it to keep it, and must not free it. A caller that shows the frames uses the other [frame].
     */
    fun frame(size: Int, nowMs: Long = System.currentTimeMillis()): Bitmap = frame(size, nowMs) { true }!!

    /**
     * Like the other [frame], drawn only into a bitmap that [isFree] says the caller no longer shows
     * and will not show again: null, and nothing drawn, when all [BUFFERS] are still in use. So a
     * frame is never drawn into a bitmap that is on the screen.
     */
    fun frame(size: Int, nowMs: Long, isFree: (Bitmap) -> Boolean): Bitmap? {
        val index = freeBuffer(size, isFree) ?: return null
        val target = buffers[index]!!
        val drawing = drawing()
        drawing.frameTime = nowMs
        // The kept layer pictures have the old size: the screen turned, or another screen shows the preview
        if (size != drawing.size) {
            drawing.size = size
            drawing.invalidate(all = true)
        }
        // The hour, minute and date fields are filled with the data, from the real clock
        val minute = nowMs / 60_000L
        if (minute != drawing.minute) {
            drawing.minute = minute
            drawing.invalidate(all = false)
        }
        val wantsAnimation = frameDelayMs(Long.MAX_VALUE) != Long.MAX_VALUE
        // A frame of the animation draws over the views: they only need the clock of now, unless they
        // are out of date. Then no still frame is drawn at all.
        if (wantsAnimation && !drawing.stale) {
            showInstant(drawing.face, size, nowMs)
            if (drawAnimation(drawing, index, size, nowMs)) return target
        }
        val still = drawing.pipeline.compose(nowMs, ambient = false, width = size, height = size)
        drawing.stale = false
        // Also when the animation plays: this sets the clock and the values in the views it draws over
        if (wantsAnimation && drawAnimation(drawing, index, size, nowMs)) {
            still.recycle()
            return target
        }
        drawing.canvasFor(index).drawBitmap(still, 0f, 0f, copyPaint)
        still.recycle()
        return target
    }

    /**
     * The index of the buffer for the next frame, of [size] pixels: the first one, in turn from the one
     * after the last frame, that [isFree] gives free (or that was never made); null when none is. A
     * buffer of another size is made again, never freed: the screen may still show it.
     */
    private fun freeBuffer(size: Int, isFree: (Bitmap) -> Boolean): Int? {
        for (k in 0 until BUFFERS) {
            val index = (nextBuffer + k) % BUFFERS
            val kept = buffers[index]
            if (kept != null && !isFree(kept)) continue
            nextBuffer = (index + 1) % BUFFERS
            if (kept == null || kept.isRecycled || kept.width != size || kept.height != size) buffers[index] = createBitmap(size, size)
            return index
        }
        return null
    }

    /**
     * Puts the views at the clock of [nowMs], for a frame of the animation that does not go through the
     * pipeline: the steps of `CwfFramePipeline.compose` before it draws, without reloading the values
     * and without drawing the still picture.
     */
    private fun showInstant(face: CustomWatchface, size: Int, nowMs: Long) {
        face.prepareLayout(size, size)
        face.setRenderAmbient(false)
        face.setRenderInstant(nowMs)
        face.updateSecondVisibility()
        face.setSecond()
        face.setRenderInstant(null)
    }

    /** Draws a frame of the animation at [nowMs] into the buffer [index]; false when it does not play in this frame. */
    private fun drawAnimation(drawing: Drawing, index: Int, size: Int, nowMs: Long): Boolean {
        val content = drawing.face.styleId * 31 + dataVersion
        // The values of the frame (the moon, the ages, the date) at the instant of the frame
        drawing.face.setRenderInstant(nowMs)
        try {
            return drawing.wakeAnimator.drawFrame(
                drawing.canvasFor(index), size, size, nowMs, content, emptyList<Any?>(), drawing.face.wakeParts, allowed = true, ready = true
            )
        } finally {
            drawing.face.setRenderInstant(null)
        }
    }

    /**
     * Frees the face, its views and its pictures while no preview is shown. The values, the design
     * and the settings stay. The next [frame] builds the face again, and the animation plays again,
     * as on the watch when the face becomes visible.
     */
    fun release() {
        drawing?.release()
        drawing = null
    }

    /** The watch settings the phone can set, by their stored names. */
    private val settingNames: CwfSettingNames by lazy { CwfSettingNames(host.resources) }

    /**
     * Writes the watch settings [values] (stored name -> value, as in `CwfSettingsSync`) into
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

        /**
         * How many bitmaps the frames are drawn into. Three: one on the screen, one handed out and not yet
         * shown, and one to draw the next frame into.
         */
        const val BUFFERS = 3
    }
}
