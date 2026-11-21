// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.complications.wff

import android.app.PendingIntent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.complications.ComplicationAction
import app.aaps.wear.complications.ModernBaseComplicationProviderService
import app.aaps.wear.data.ComplicationData as ComplicationStore
import app.aaps.wear.watchfaces.wff.WffFace
import dev.zacsweers.metro.HasMemberInjections
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The glucose for a Watch Face Format (WFF) face, as one `RANGED_VALUE`:
 *
 * - value: the glucose in mg/dl, the same number as `v_sgv` in a design, kept inside 39 to 400
 *   ([GlucoseRange]). The face colours it and turns gauge hands with it, with the wearer's
 *   thresholds written into the face as numbers.
 * - text: the glucose as the phone formatted it, in the wearer's unit, without the arrow.
 * - title: the delta, in the wearer's unit.
 * - monochromatic image: the arrow, the face's own picture where its `data.zip` has one. A face
 *   draws it without a tint, so it keeps its colours. (A small image is not drawn inside a
 *   `RANGED_VALUE` complication; checked on the Wear OS 6 emulator.)
 *
 * Without a reading it sends no data, so the face shows its "no reading" look.
 * The age of the reading is not here: [WffGlucoseAgeComplication] sends it as a value that keeps
 * counting while our process sleeps.
 */
@HasMemberInjections
class WffGlucoseComplication : ModernBaseComplicationProviderService() {

    @Inject lateinit var wffFace: WffFace

    private val arrowScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** The arrows of the loaded face, read before each update. Null: the app's own arrows. */
    @Volatile private var designArrows: Map<String, Icon>? = null

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        // Read the face's arrows first (a file read), then let the base class build the data.
        // A failure only means the app's own arrows are used.
        arrowScope.launch {
            designArrows = try {
                GlucoseArrows.fromDesign(wffFace.dataDesign(), resources)
            } catch (e: Exception) {
                aapsLogger.error(LTag.WEAR, "WffGlucoseComplication: design arrows not read", e)
                null
            }
            super.onComplicationRequest(request, listener)
        }
    }

    override fun onDestroy() {
        arrowScope.cancel()
        super.onDestroy()
    }

    override fun buildComplicationData(type: ComplicationType, data: ComplicationStore, complicationPendingIntent: PendingIntent): ComplicationData? {
        if (type != ComplicationType.RANGED_VALUE) return null
        val bg = data.bgData
        if (!GlucoseRange.hasReading(bg)) return NoDataComplicationData()
        val arrow = designArrows?.get(bg.slopeArrow) ?: GlucoseArrows.appArrow(this, bg.slopeArrow)
        return RangedValueComplicationData.Builder(
            value = GlucoseRange.value(bg.sgv),
            min = GlucoseRange.MIN,
            max = GlucoseRange.MAX,
            contentDescription = PlainComplicationText.Builder("${bg.sgvString} ${bg.slopeArrow}").build()
        )
            .setText(PlainComplicationText.Builder(bg.sgvString).build())
            .setTitle(PlainComplicationText.Builder(bg.delta).build())
            .setMonochromaticImage(MonochromaticImage.Builder(arrow).build())
            .setTapAction(complicationPendingIntent)
            .build()
    }

    override fun getComplicationAction(): ComplicationAction = ComplicationAction.MENU
}
