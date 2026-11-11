package app.aaps.core.interfaces.maintenance

/**
 * Interface for maintenance functionality.
 * Allows access to maintenance plugin through interface lookup.
 */
interface Maintenance {

    suspend fun executeSendLogs(): ExportResult

    /**
     * Zips the recent logs into the public Downloads folder of the device, so the user can take
     * them without sending an email.
     *
     * @return the name of the saved file, or null when it could not be saved
     * @throws NotImplementedError on a platform that cannot do it yet. The default body says so, so
     *   only Android has to implement it.
     */
    suspend fun saveLogsToDownloads(): String? = throw NotImplementedError("Maintenance.saveLogsToDownloads")

    fun deleteLogs(keep: Int)
}
