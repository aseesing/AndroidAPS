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
 * - files (the face's DataStore) go to their own folder, see [getFilesDir]. That folder is emptied
 *   when the host is made, once per run of the app;
 * - preferences go to files that start with [NAME], see [getSharedPreferences];
 * - events go to an own [RxBus], so nothing the face sends reaches the phone's bus.
 *
 * The watch settings the face reads (show seconds, which values to show, ...) are in [watchSettings].
 *
 * Only one instance may exist in a process: the face's DataStore allows one owner per file.
 */
class PreviewHost(base: Context, private val logger: AAPSLogger) : ContextWrapper(base.applicationContext), MetroMemberInjector {

    init {
        // The face's DataStore keeps the values and the design on disk. Removed before anything reads
        // it, so the preview only shows what the phone sent in this run of the app, never old values.
        File(baseContext.filesDir, NAME).deleteRecursively()
    }

    private val ownFilesDir: File by lazy { File(baseContext.filesDir, NAME).apply { mkdirs() } }

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
        baseContext.getSharedPreferences("${NAME}_$name", mode)

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

    private companion object {

        /** The folder under the phone's files folder, and the start of the preferences file names. */
        const val NAME = "cwf_preview"

        /** The watch settings file, after [NAME]. */
        const val SETTINGS_FILE = "watch_settings"
    }
}
