// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.phonepreview

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import app.aaps.core.interfaces.di.MetroMemberInjector
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.shared.impl.rx.bus.RxBusImpl
import app.aaps.shared.impl.sharedPreferences.SPImpl
import app.aaps.shared.impl.utils.DateUtilImpl
import app.aaps.wear.data.ComplicationDataRepository
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.watchfaces.utils.BaseWatchFace
import app.aaps.wear.watchfaces.utils.SimpleUi
import java.io.File

/**
 * The world the watch face lives in when it runs on the phone.
 *
 * The watch face fills its `@Inject` fields through its application context (see
 * `BaseWatchFace.prepareForRendering`). On the phone that would be the phone app, whose graph knows
 * nothing about watch faces. So this wrapper says it is the application context itself, and fills the
 * fields with its own objects. The phone's dependency graph is not touched.
 *
 * Everything the face stores is kept apart from the phone's own data:
 * - files (the face's DataStore) go to their own folder, see [getFilesDir];
 * - preferences go to files that start with the [name], see [getSharedPreferences];
 * - events go to an own [RxBus], so nothing the face sends reaches the phone's bus.
 *
 * The watch settings the face reads (show seconds, which fields to show, ...) are in [watchSettings].
 *
 * Only one instance per [name] may exist in a process: the face's DataStore allows one owner per
 * file. Two previews at once (the day face and the night face) each have their own [name].
 *
 * @param name the folder of the files and the start of the preferences file names
 */
class PreviewHost(
    base: Context,
    private val logger: AAPSLogger,
    private val name: String = DEFAULT_NAME
) : ContextWrapper(base.applicationContext), MetroMemberInjector {

    private val ownFilesDir: File by lazy { File(baseContext.filesDir, name).apply { mkdirs() } }

    /** The preferences of the watch face: the watch settings on the watch, these settings on the phone. */
    val watchSettings: SharedPreferences by lazy { getSharedPreferences(SETTINGS_FILE, MODE_PRIVATE) }

    val sp: SP by lazy { SPImpl(watchSettings, this) }
    val rxBus: RxBus by lazy { RxBusImpl(logger) }
    val dateUtil: DateUtil by lazy { DateUtilImpl(this) }
    val repository: ComplicationDataRepository by lazy { ComplicationDataRepository(this, logger, sp) }
    private val simpleUi: SimpleUi by lazy { SimpleUi(this, sp, dateUtil) }

    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = ownFilesDir

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences("${this.name}_$name", mode)

    override fun injectMembers(target: Any): Boolean {
        if (target !is BaseWatchFace) return false
        target.complicationDataRepository = repository
        target.aapsLogger = logger
        target.rxBus = rxBus
        target.sp = sp
        target.dateUtil = dateUtil
        target.simpleUi = simpleUi
        if (target is CustomWatchface) target.context = this
        return true
    }

    companion object {

        /** The name of the first preview: its folder under the phone's files folder, and its preferences prefix. */
        const val DEFAULT_NAME = "cwf_preview"

        /** The watch settings file, after the name of the preview. */
        const val SETTINGS_FILE = "watch_settings"
    }
}
