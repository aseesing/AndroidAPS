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
import app.aaps.wear.health.HealthValues
import app.aaps.wear.watchfaces.CustomWatchface
import app.aaps.wear.watchfaces.daynight.StoredWeather
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
 * - preferences go to files that start with the [name], see [getSharedPreferences];
 * - events go to an own [RxBus], so nothing the face sends reaches the phone's bus.
 *
 * The watch settings the face reads (show seconds, which values to show, ...) are in [watchSettings].
 *
 * Only one instance per [name] may exist in a process: the face's DataStore allows one owner per
 * file. Two previews at once (the day face and the night face) each have their own [name]; the watch
 * face designs repository uses its own name too.
 *
 * @param name the folder of the files and the start of the preferences file names
 */
class PreviewHost(
    base: Context,
    private val logger: AAPSLogger,
    private val name: String = DEFAULT_NAME // one host per face
) : ContextWrapper(base.applicationContext), MetroMemberInjector {

    init {
        // The face's DataStore keeps the values and the design on disk. Removed before anything reads
        // it, so the preview only shows what the phone sent in this run of the app, never old values.
        File(baseContext.filesDir, name).deleteRecursively()
    }

    private val ownFilesDir: File by lazy { File(baseContext.filesDir, name).apply { mkdirs() } }

    /** The preferences of the watch face: the watch settings on the watch, these settings on the phone. */
    val watchSettings: SharedPreferences by lazy { getSharedPreferences(SETTINGS_FILE, MODE_PRIVATE) }

    val sp: SP by lazy { SPImpl(watchSettings, this) }
    val rxBus: RxBus by lazy { RxBusImpl(logger) }
    val dateUtil: DateUtil by lazy { DateUtilImpl(this) }
    val repository: ComplicationDataRepository by lazy { ComplicationDataRepository(this, logger, sp) }
    private val simpleUi: SimpleUi by lazy { SimpleUi(this, sp, dateUtil) }

    /**
     * The heart rate and steps store of the face, in the same file as on the watch (see
     * `HealthModule`), here behind [name]. The watch does not send these values, so on the phone it stays empty.
     */
    val healthValues: HealthValues by lazy { HealthValues(getSharedPreferences(HealthValues.FILE, MODE_PRIVATE)) }

    init {
        // The weather is a value too, kept in the preferences: removed for the same reason.
        // Here, after the properties it needs.
        StoredWeather.clear(sp)
    }

    override fun getApplicationContext(): Context = this

    override fun getFilesDir(): File = ownFilesDir

    // The night face file of the face (see `ComplicationDataRepository`) stays apart too:
    // two previews at once must not share one DataStore file, and the phone's own folder is not the face's.
    // In the phone's no-backup folder, as on the watch: that file can hold the user's place (the rule
    // for when it is day), which must never be in a backup or a move to a new phone.
    override fun getNoBackupFilesDir(): File = ownNoBackupDir

    // Emptied when the host is made, like the files above
    private val ownNoBackupDir: File by lazy { File(baseContext.noBackupFilesDir, name).apply { deleteRecursively(); mkdirs() } }

    override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences =
        baseContext.getSharedPreferences("${this.name}_$name", mode)

    override fun injectMembers(target: Any): Boolean {
        if (target !is BaseWatchFace) return false
        target.complicationDataRepository = repository
        target.aapsLogger = logger
        target.rxBus = rxBus
        target.sp = sp
        // The clock fields of the face show the instant it draws for, see RenderInstantDateUtil
        target.dateUtil = if (target is CustomWatchface) RenderInstantDateUtil(dateUtil) { target.renderInstantOrNull() } else dateUtil
        target.simpleUi = simpleUi
        if (target is CustomWatchface) {
            target.context = this
            target.healthValues = healthValues // heart rate and steps
        }
        return true
    }

    companion object { // public, for the name of the night preview

        /** The name of the first preview: its folder under the phone's files folder, and its preferences prefix. */
        const val DEFAULT_NAME = "cwf_preview"

        /** The watch settings file, after the name of the preview. */
        private const val SETTINGS_FILE = "watch_settings"
    }
}
