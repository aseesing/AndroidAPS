package app.aaps.core.interfaces.maintenance

/**
 * Interface for maintenance functionality.
 * Allows access to maintenance plugin through interface lookup.
 */
interface Maintenance {

    suspend fun executeSendLogs(): ExportResult

    /**
     * True when this platform can do [saveLogsToDownloads]. When it is false, the maintenance
     * sheet does not show the item at all. False by default, so only Android has to say yes.
     */
    val canSaveLogsToDownloads: Boolean get() = false

    /**
     * Zips the recent logs into the public Downloads folder of the device, so the user can take
     * them without sending an email.
     *
     * @return the name of the saved file, or null when it could not be saved
     * @throws NotImplementedError where [canSaveLogsToDownloads] is false. The default body says
     *   so, so only Android has to implement it.
     */
    suspend fun saveLogsToDownloads(): String? = throw NotImplementedError("Maintenance.saveLogsToDownloads")

    fun deleteLogs(keep: Int)
}
