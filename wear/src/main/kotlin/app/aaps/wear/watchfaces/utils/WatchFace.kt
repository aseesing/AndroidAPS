/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2015 ustwo studio inc (www.ustwo.com)
 *
 * Adapted for AAPS
 * Migrated to AndroidX WatchFaceService
 */

package app.aaps.wear.watchfaces.utils

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Rect
import android.text.format.DateFormat
import android.view.SurfaceHolder
import android.view.WindowInsets
import androidx.wear.watchface.CanvasType
import androidx.wear.watchface.ComplicationSlot
import androidx.wear.watchface.ComplicationSlotsManager
import androidx.wear.watchface.DrawMode
import androidx.wear.watchface.RenderParameters
import androidx.wear.watchface.Renderer
import androidx.wear.watchface.TapEvent
import androidx.wear.watchface.WatchFace
import androidx.wear.watchface.WatchFaceService
import androidx.wear.watchface.WatchFaceType
import androidx.wear.watchface.style.WatchFaceLayer
import androidx.wear.watchface.WatchState
import androidx.wear.watchface.style.CurrentUserStyleRepository
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * Abstract base class extending WatchFaceService for Android Wear watch faces.
 * Manages time updates, display modes, and rendering.
 *
 * Migrated to AndroidX WatchFace API while maintaining backward compatibility
 * with existing watchface implementations.
 */
@SuppressLint("Deprecated")
abstract class WatchFace : WatchFaceService() {

    // State accessible to subclasses
    protected var faceRect = Rect()
    protected var is24HourFormat = false
    protected val previousTime = WatchFaceTime()
    protected val latestTime = WatchFaceTime()
    protected var isAmbient = false
    protected var lowBitAmbient = false
    protected var burnInProtection = false
    protected var watchShape = WatchShape.UNKNOWN
    protected var layoutComplete = false

    // Stores the manager passed into createWatchFace() (previously discarded). Empty for every
    // watch face except those that override createComplicationSlotsManager() with real slots.
    protected var complicationSlotsManager: ComplicationSlotsManager? = null

    protected val currentWatchMode: WatchMode
        get() = when {
            !isAmbient                        -> WatchMode.INTERACTIVE
            burnInProtection && lowBitAmbient -> WatchMode.LOW_BIT_BURN_IN
            burnInProtection                  -> WatchMode.BURN_IN
            lowBitAmbient                     -> WatchMode.LOW_BIT
            else                              -> WatchMode.AMBIENT
        }

    fun getWidth(): Int = faceRect.width()
    fun getHeight(): Int = faceRect.height()
    fun getTime(): WatchFaceTime = latestTime

    // Abstract methods for subclasses (maintain backward compatibility)

    /**
     * Get update rate for interactive mode in milliseconds.
     *
     * Determines how frequently the watchface redraws when in interactive (active) mode.
     * Faster rates provide smoother animations (e.g., second hand) but consume more battery.
     *
     * Default: 60000ms (1 minute) - updates only when minute changes
     * Common values:
     * - 1000ms: Update every second (for second hand)
     * - 60000ms: Update every minute (battery efficient)
     *
     * @return Update interval in milliseconds
     */
    protected open fun getInteractiveModeUpdateRate(): Long = 60 * 1000L

    /**
     * Called when watchface layout is established.
     *
     * Invoked once after watchface service starts, providing screen geometry info.
     * Use this to calculate layout positions, sizes, and prepare view measurements.
     *
     * @param shape Screen shape (ROUND, SQUARE, or RECTANGLE)
     * @param screenBounds Full screen dimensions as Rect
     * @param screenInsets System UI insets (chin, notches, etc.) - may be null
     */
    @Suppress("SameParameterValue")
    protected open fun onLayout(shape: WatchShape, screenBounds: Rect, screenInsets: WindowInsets?) {}

    /**
     * Called when time changes.
     *
     * Invoked when time updates according to interactive mode update rate.
     * Typically used to detect minute/hour changes and trigger data updates.
     *
     * Default behavior: No-op. Override to handle time-based updates.
     *
     * @param oldTime Previous time value
     * @param newTime Current time value (use hasMinuteChanged(), hasHourChanged())
     */
    protected open fun onTimeChanged(oldTime: WatchFaceTime, newTime: WatchFaceTime) {}

    /**
     * Render the watchface on the canvas.
     *
     * Called whenever the watchface needs to be drawn:
     * - Time updates (based on interactive mode update rate)
     * - Watch mode changes (interactive ↔ ambient)
     * - Data updates (BG, IOB, etc.)
     * - Invalidation requests
     *
     * Implementation must:
     * - Draw all watchface elements (time, date, BG, complications, charts, etc.)
     * - Adapt rendering for current watch mode (use isAmbient(), isLowBitAmbient())
     * - Use lazy initialization pattern (check ::binding.isInitialized)
     * - Be fast (<16ms for 60fps, ideally <5ms)
     *
     * Rendering guidelines:
     * - Interactive mode: Full color, anti-aliasing, animations
     * - Ambient mode: Black & white only, no anti-aliasing, simplified
     * - Low-bit ambient: Additional restrictions (no gradients)
     *
     * @param canvas Drawing canvas to render watchface on
     */
    protected abstract fun onDraw(canvas: Canvas)

    /**
     * Called when watch mode changes.
     *
     * Invoked when watch transitions between modes:
     * - INTERACTIVE: Watch is active, full color rendering
     * - AMBIENT: Watch in always-on display, power-saving mode
     * - LOW_BIT: Ambient with limited color depth (black & white only)
     * - LOW_BIT_BURN_IN: Low-bit with burn-in protection requirements
     *
     * Typical actions:
     * - Update colors (setColorDark/Bright/LowRes)
     * - Adjust second hand visibility
     * - Simplify rendering for ambient modes
     * - Trigger redraw
     *
     * @param watchMode New watch mode
     */
    protected open fun onWatchModeChanged(watchMode: WatchMode) {}

    /**
     * Called when 12/24-hour format preference changes.
     *
     * Invoked when user changes time display format in system settings.
     * Allows watchface to adapt time formatting (AM/PM vs 24-hour).
     *
     * Default behavior: No-op. Override if watchface displays time.
     *
     * @param is24HourFormat true for 24-hour format, false for 12-hour with AM/PM
     */
    protected open fun on24HourFormatChanged(is24HourFormat: Boolean) {}

    /**
     * Called when user taps the watchface.
     *
     * Handles touch events on the watchface screen.
     * Use to implement interactive elements (e.g., double-tap to open menu, tap chart to zoom).
     *
     * Common tap types (from WatchFaceService):
     * - TAP_TYPE_TOUCH: Initial touch
     * - TAP_TYPE_TAP: Quick tap (touch + release)
     * - TAP_TYPE_TOUCH_CANCEL: Touch cancelled
     *
     * Typical implementation checks tap location against UI element bounds
     * and performs actions (open activity, change chart timeframe, etc.).
     *
     * @param tapType Type of tap event
     * @param x X coordinate of tap in pixels
     * @param y Y coordinate of tap in pixels
     * @param eventTime Timestamp of tap event in milliseconds
     */
    protected open fun onTapCommand(tapType: Int, x: Int, y: Int, eventTime: Long) {}

    /**
     * Called after complications have rendered, so a subclass can draw content that must sit on top
     * of them - e.g. mainLayout's own draw, deferred via [BaseWatchFace.deferMainLayoutDraw]. No-op
     * for watch faces that draw everything inside [onDraw].
     */
    protected open fun onDrawOverlay(canvas: Canvas) {}

    /**
     * Decides how [slot] is painted this frame, letting a subclass supply bounds per frame instead of
     * being stuck with the ones the slot was created with. A slot's own bounds and enabled flag have
     * `internal` setters, so they cannot be changed after [createComplicationSlotsManager]; rendering
     * has no such restriction, since `CanvasComplication.render` takes bounds per call.
     *
     * **Tap hit-testing still uses the slot's declared bounds, not these.** A subclass that paints a
     * slot away from where it was declared must also bring the declared bounds up to date, or taps
     * land on the wrong complication - see [WatchFaceComplications.syncGeometry].
     *
     * `internal` rather than `protected` because [ComplicationRender] is an internal type and a
     * protected member of a public class may not expose one.
     */
    internal open fun complicationRender(slot: ComplicationSlot): ComplicationRender = ComplicationRender.Declared

    /**
     * Counts the changes announced through [invalidate]: data, preferences, mode, taps. A subclass that
     * keeps drawn pictures between frames compares it to know when they are out of date. Frames the
     * library schedules on its own, once a second or once a minute, do not count.
     */
    protected var contentVersion = 0
        private set

    /**
     * Lets a subclass draw a whole interactive frame its own way, from pictures it kept, instead of
     * [onDraw], the complications and [onDrawOverlay]. Asked only for a plain frame on the watch: not
     * ambient, not the editor's highlight, not a preview with layers left out. Return false to draw the
     * normal way.
     */
    protected open fun drawLayeredFrame(canvas: Canvas, bounds: Rect, zonedDateTime: ZonedDateTime): Boolean = false

    /** Paints every complication slot as the normal frame does, for a subclass that draws a frame its own way. */
    protected fun drawComplicationSlots(canvas: Canvas, zonedDateTime: ZonedDateTime) {
        renderer?.drawComplications(canvas, zonedDateTime)
    }

    /**
     * What the complication slots show now. The library hands every slot a new object when its data
     * changes, so a subclass can compare these by reference.
     */
    protected fun complicationDataNow(): List<Any?> =
        complicationSlotsManager?.complicationSlots?.values?.map { it.complicationData.value } ?: emptyList()

    /** Hands the watch state (ambient, visible, headless) to a subclass that wants to follow it. */
    protected open fun onWatchStateReady(watchState: WatchState) {}

    /** PERSONAL PATCH: keeps the first frame after a wake cheap, see [FirstFrameAfterWake]. */
    protected val wakeFrame = FirstFrameAfterWake()

    // Renderer instance
    private var renderer: WatchFaceRenderer? = null

    /**
     * Asks the system to re-render the thumbnail it caches for this watch face. Needed by a watch face
     * whose appearance comes from outside the style schema - a loaded template, say - since the system
     * otherwise has no reason to redo it.
     *
     * Call only when the appearance really changed: the system may rate limit these requests. Safe at
     * any time, though - the library queues the request until a renderer exists.
     */
    protected fun requestPreviewImageUpdate() {
        renderer?.sendPreviewImageNeedsUpdateRequest()
    }

    override suspend fun createWatchFace(
        surfaceHolder: SurfaceHolder,
        watchState: WatchState,
        complicationSlotsManager: ComplicationSlotsManager,
        currentUserStyleRepository: CurrentUserStyleRepository
    ): WatchFace {
        this.complicationSlotsManager = complicationSlotsManager
        onWatchStateReady(watchState)
        wakeFrame.watch(watchState) // PERSONAL PATCH

        // Create renderer that delegates to abstract methods
        renderer = WatchFaceRenderer(
            context = applicationContext,
            surfaceHolder = surfaceHolder,
            wState = watchState,
            currentUserStyleRepository = currentUserStyleRepository
        )

        return WatchFace(
            watchFaceType = WatchFaceType.DIGITAL,
            renderer = renderer!!
        ).setTapListener(object : WatchFace.TapListener {
            override fun onTapEvent(tapType: Int, tapEvent: TapEvent, complicationSlot: ComplicationSlot?) {
                // Ignore complication taps - they're handled by the system
                if (complicationSlot != null) return

                // Call the abstract onTapCommand method with legacy parameters for backward compatibility
                // TapEvent provides x, y coordinates and Instant timestamp
                onTapCommand(tapType, tapEvent.xPos, tapEvent.yPos, tapEvent.tapTime.toEpochMilli())
            }
        })
    }

    /**
     * Inner renderer class that bridges new AndroidX API with old abstract methods
     */
    // Shared assets for renderer (no assets needed for this implementation)
    class RendererSharedAssets : Renderer.SharedAssets {

        override fun onDestroy() {}
    }

    private inner class WatchFaceRenderer(
        private val context: Context,
        surfaceHolder: SurfaceHolder,
        private val wState: WatchState,
        currentUserStyleRepository: CurrentUserStyleRepository
    ) : Renderer.CanvasRenderer2<RendererSharedAssets>(
        surfaceHolder,
        currentUserStyleRepository,
        wState,
        CanvasType.HARDWARE,
        interactiveDrawModeUpdateDelayMillis = getInteractiveModeUpdateRate(),
        clearWithBackgroundTintBeforeRenderingHighlightLayer = false
    ) {

        private val dateTimeChangedReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                updateTimeAndInvalidate()
            }
        }

        init {
            // Register broadcast receiver for time changes
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
            }
            context.registerReceiver(dateTimeChangedReceiver, filter)
        }

        override suspend fun createSharedAssets(): RendererSharedAssets {
            return RendererSharedAssets()
        }

        override fun render(
            canvas: Canvas,
            bounds: Rect,
            zonedDateTime: ZonedDateTime,
            sharedAssets: RendererSharedAssets
        ) {
            // Update bounds if changed
            if (faceRect.width() != bounds.width() || faceRect.height() != bounds.height()) {
                faceRect.set(bounds)

                // Approximate watch shape from bounds
                watchShape = if (bounds.width() == bounds.height()) {
                    WatchShape.CIRCLE
                } else {
                    WatchShape.SQUARE
                }

                if (!layoutComplete) {
                    // Pass null for WindowInsets as it's not available in new API
                    onLayout(watchShape, faceRect, null)
                    layoutComplete = true
                }
            }

            // Update watch state
            val newAmbient = renderParameters.drawMode == DrawMode.AMBIENT
            wakeFrame.beginFrame(leftAmbient = isAmbient && !newAmbient, interactive = !newAmbient && !wState.isHeadless) // PERSONAL PATCH
            if (isAmbient != newAmbient) {
                isAmbient = newAmbient
                onWatchModeChanged(currentWatchMode)
            }

            lowBitAmbient = wState.hasLowBitAmbient
            burnInProtection = wState.hasBurnInProtection

            // Update time from ZonedDateTime
            updateTime(zonedDateTime)

            val plainFrame = renderParameters.drawMode == DrawMode.INTERACTIVE &&
                renderParameters.highlightLayer == null &&
                renderParameters.watchFaceLayers == WatchFaceLayer.ALL_WATCH_FACE_LAYERS
            // PERSONAL PATCH: the wake frame is drawn the plain way, one pass over the views; the
            // layered frame and the wake animation would first draw their kept pictures again
            if (!plainFrame || wakeFrame.isActive || !drawLayeredFrame(canvas, bounds, zonedDateTime)) {
                // Delegate to abstract onDraw method
                onDraw(canvas)
                drawComplications(canvas, zonedDateTime)
                onDrawOverlay(canvas)
            }
            wakeFrame.endFrame() // PERSONAL PATCH

            // Read again after every frame, not only once at construction: whether seconds are shown
            // is known only after the first draw has loaded the design, and changes with preferences.
            // The library reads this before it schedules the next frame, and rounds a long delay to
            // the start of the next minute. Without it a face drew 60 frames a minute for a clock that
            // changes once a minute.
            interactiveDrawModeUpdateDelayMillis = getInteractiveModeUpdateRate()
        }

        /**
         * Generic render pass for whatever complication slots exist. Empty/no-op for every watch face
         * that doesn't declare any (the default). What each slot's bounds are - and whether it is
         * painted at all - is the subclass's call, see [complicationRender].
         */
        fun drawComplications(canvas: Canvas, zonedDateTime: ZonedDateTime) {
            complicationSlotsManager?.complicationSlots?.values?.forEach { slot ->
                if (!slot.enabled) return@forEach
                when (val render = complicationRender(slot)) {
                    ComplicationRender.Declared -> slot.render(canvas, zonedDateTime, renderParameters)
                    ComplicationRender.Skip     -> Unit
                    is ComplicationRender.At    -> withRotation(canvas, render) {
                        slot.renderer.render(canvas, render.bounds, zonedDateTime, renderParameters, slot.id)
                    }
                }
            }
        }

        override fun renderHighlightLayer(
            canvas: Canvas,
            bounds: Rect,
            zonedDateTime: ZonedDateTime,
            sharedAssets: RendererSharedAssets
        ) {
            canvas.drawColor(renderParameters.highlightLayer!!.backgroundTint)
            complicationSlotsManager?.complicationSlots?.values?.forEach { slot ->
                if (!slot.enabled) return@forEach
                when (val render = complicationRender(slot)) {
                    ComplicationRender.Declared -> slot.renderHighlightLayer(canvas, zonedDateTime, renderParameters)
                    ComplicationRender.Skip     -> Unit
                    is ComplicationRender.At    -> withRotation(canvas, render) {
                        drawComplicationHighlight(canvas, slot, render.bounds, zonedDateTime)
                    }
                }
            }
        }

        /**
         * The [ComplicationSlot.renderHighlightLayer] logic, with the bounds supplied by
         * [complicationRender] instead of the slot's own, so the editor's selection highlight lands
         * on the complication where it was actually painted.
         */
        private fun drawComplicationHighlight(canvas: Canvas, slot: ComplicationSlot, bounds: Rect, zonedDateTime: ZonedDateTime) {
            // Only sensible for editable slots - a fixed data source can't be picked, so the system
            // never highlights it.
            if (slot.fixedComplicationDataSource) return
            val highlightLayer = renderParameters.highlightLayer ?: return
            val highlighted = when (val element = highlightLayer.highlightedElement) {
                is RenderParameters.HighlightedElement.AllComplicationSlots -> true
                is RenderParameters.HighlightedElement.ComplicationSlot     -> element.id == slot.id
                else                                                       -> false
            }
            if (highlighted)
                slot.renderer.drawHighlight(canvas, bounds, slot.boundsType, zonedDateTime, highlightLayer.highlightTint)
        }

        /** Runs [draw] with the canvas rotated as [render] asks, matching what every other CWF view
         *  already does with its own `rotation`. A no-op save/restore when there's no rotation. */
        private inline fun withRotation(canvas: Canvas, render: ComplicationRender.At, draw: () -> Unit) {
            if (render.rotation == 0f) {
                draw()
                return
            }
            canvas.save()
            canvas.rotate(render.rotation, render.bounds.exactCenterX(), render.bounds.exactCenterY())
            draw()
            canvas.restore()
        }

        private fun updateTime(zonedDateTime: ZonedDateTime) {
            previousTime.set(latestTime)

            // Convert ZonedDateTime to WatchFaceTime
            latestTime.year = zonedDateTime.year
            latestTime.month = zonedDateTime.monthValue - 1 // Calendar uses 0-based months
            latestTime.monthDay = zonedDateTime.dayOfMonth
            latestTime.hour = zonedDateTime.hour
            latestTime.minute = zonedDateTime.minute
            latestTime.second = zonedDateTime.second
            latestTime.timezone = zonedDateTime.zone.id

            onTimeChanged(previousTime, latestTime)

            val is24Hour = DateFormat.is24HourFormat(context)
            if (is24Hour != is24HourFormat) {
                is24HourFormat = is24Hour
                on24HourFormatChanged(is24HourFormat)
            }
        }

        private fun updateTimeAndInvalidate() {
            // Update from system time
            previousTime.set(latestTime)
            latestTime.setToNow()
            latestTime.timezone = TimeZone.getDefault().id

            onTimeChanged(previousTime, latestTime)

            val is24Hour = DateFormat.is24HourFormat(context)
            if (is24Hour != is24HourFormat) {
                is24HourFormat = is24Hour
                on24HourFormatChanged(is24HourFormat)
            }

            invalidate()
        }

        override fun onDestroy() {
            super.onDestroy()
            wakeFrame.release() // PERSONAL PATCH
            try {
                context.unregisterReceiver(dateTimeChangedReceiver)
            } catch (_: IllegalArgumentException) {
                // Receiver not registered, ignore
            }
        }
    }

    // Helper method for subclasses to trigger invalidation
    fun invalidate() {
        contentVersion++
        renderer?.invalidate()
    }

    override fun getSystemService(name: String): Any? {
        // Delegate to application context for backward compatibility
        return applicationContext.getSystemService(name) ?: super.getSystemService(name)
    }
}
