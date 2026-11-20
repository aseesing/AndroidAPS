// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.complications.wff

import android.app.PendingIntent
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import app.aaps.core.interfaces.logging.LTag
import app.aaps.wear.complications.ComplicationAction
import app.aaps.wear.complications.ComplicationTapActivity
import app.aaps.wear.complications.ModernBaseComplicationProviderService
import app.aaps.wear.data.ComplicationData as ComplicationStore
import dev.zacsweers.metro.HasMemberInjections
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * The data picture of a Watch Face Format face, as one `SMALL_IMAGE`: everything of the design that
 * shows AAPS data and that the face does not draw itself (the graph, the status texts, gauge hands,
 * the sky), see [WffDataPicture]. The face lays it over its static picture, as large as the screen.
 *
 * It is made again when data arrives and every five minutes. A failure sends no data: the face then
 * shows its live parts (the clock, the glucose, its age) and its static art, and the next request
 * tries again.
 */
@HasMemberInjections
class WffDataComplication : ModernBaseComplicationProviderService() {

    private val drawScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onComplicationRequest(request: ComplicationRequest, listener: ComplicationRequestListener) {
        val tap = ComplicationTapActivity.getTapActionIntent(this, ComponentName(this, WffDataComplication::class.java), request.complicationInstanceId, ComplicationAction.MENU)
        drawScope.launch {
            val data = try {
                if (request.complicationType == ComplicationType.SMALL_IMAGE) picture(tap) else null
            } catch (e: Exception) {
                aapsLogger.error(LTag.WEAR, "WffDataComplication: the data picture was not made", e)
                null
            }
            listener.onComplicationData(data ?: NoDataComplicationData())
        }
    }

    private suspend fun picture(tap: PendingIntent): ComplicationData? {
        val design = complicationDataRepository.getCustomWatchface() ?: return null
        val painter = warm ?: WffDataPicture(applicationContext, aapsLogger).also { warm = it }
        val bitmap = painter.draw(design, System.currentTimeMillis()) ?: return null
        val bytes = withContext(Dispatchers.Default) {
            ByteArrayOutputStream(COMPRESS_HINT_BYTES).also { bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, it) }.toByteArray().also { bitmap.recycle() }
        }
        return SmallImageComplicationData.Builder(
            smallImage = SmallImage.Builder(Icon.createWithData(bytes, 0, bytes.size), SmallImageType.PHOTO).build(),
            contentDescription = PlainComplicationText.Builder("AAPS").build()
        ).setTapAction(tap).build()
    }

    override fun onDestroy() {
        drawScope.cancel()
        super.onDestroy()
    }

    /** Not used: [onComplicationRequest] draws by itself, off the base class's path. A preview has no picture. */
    override fun buildComplicationData(type: ComplicationType, data: ComplicationStore, complicationPendingIntent: PendingIntent): ComplicationData? = null

    private companion object {

        const val COMPRESS_HINT_BYTES = 64 * 1024

        /**
         * The painter, kept for the life of the process: the system binds this service only while it
         * answers, and making the face again for each request would cost most of the time (see
         * `CwfFaceComplication`). It holds the application context only.
         */
        private var warm: WffDataPicture? = null
    }
}
