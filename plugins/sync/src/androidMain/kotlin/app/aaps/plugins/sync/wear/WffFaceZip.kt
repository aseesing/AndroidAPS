package app.aaps.plugins.sync.wear

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * A Watch Face Format face zip, as the face tool makes it (see `_docs/WFF_FACE_ZIP.md`): `face.json`,
 * `data.zip`, and per Watch Face Format version and AAPS app a face APK, with a Watch Face Push token
 * for version 4. [forWatch] cuts it down to the files one watch needs, so only those are sent.
 */
object WffFaceZip {

    /** A Watch Face Format version and the lowest Android API level of a watch that runs it */
    private class Version(val number: Int, val minApiLevel: Int)

    /** Highest first: a watch gets the highest version it can run */
    private val VERSIONS = listOf(Version(4, 36), Version(3, 35), Version(2, 34))

    /** Watch Face Push needs a token, and Push is the only way to install a face on Wear OS 6 */
    private const val PUSH_VERSION = 4

    private const val FACE_JSON = "face.json"
    private const val DATA_ZIP = "data.zip"

    sealed interface Result {

        /** [zip] holds what the watch needs; [usesPush] when the watch installs it with Watch Face Push */
        class ForWatch(val zip: ByteArray, val usesPush: Boolean) : Result

        /** The zip has no face for this AAPS app: it was made for another one */
        data object OtherApp : Result

        /** The watch is too old for every face in the zip; [minApiLevel] is the lowest level they need */
        class WatchTooOld(val minApiLevel: Int) : Result

        /** Not a face zip of a known layout */
        data object Invalid : Result
    }

    /** Whether [zip] is a face zip rather than an old design zip */
    fun isFaceZip(zip: ByteArray): Boolean = FACE_JSON in read(zip)

    /** The files of [zip] for the watch of app [appId] that runs Android API level [apiLevel] */
    fun forWatch(zip: ByteArray, appId: String, apiLevel: Int): Result {
        val files = read(zip)
        if (FACE_JSON !in files || DATA_ZIP !in files) return Result.Invalid
        val forApp = VERSIONS.filter { "wff${it.number}/$appId.apk" in files && (it.number != PUSH_VERSION || "wff${it.number}/$appId.token" in files) }
        if (forApp.isEmpty()) return Result.OtherApp
        val version = forApp.firstOrNull { apiLevel >= it.minApiLevel } ?: return Result.WatchTooOld(forApp.minOf { it.minApiLevel })
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zipOut ->
            fun put(name: String, bytes: ByteArray?) {
                bytes ?: return
                zipOut.putNextEntry(ZipEntry(name))
                zipOut.write(bytes)
                zipOut.closeEntry()
            }
            put(FACE_JSON, files[FACE_JSON])
            put(DATA_ZIP, files[DATA_ZIP])
            put("face.apk", files["wff${version.number}/$appId.apk"])
            put("face.token", files["wff${version.number}/$appId.token"])
        }
        return Result.ForWatch(out.toByteArray(), usesPush = version.number == PUSH_VERSION)
    }

    /** The files of a zip by name; empty when it is not a zip */
    private fun read(zip: ByteArray): Map<String, ByteArray> {
        val files = mutableMapOf<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { input ->
                generateSequence { input.nextEntry }.filter { !it.isDirectory }.forEach { files[it.name] = input.readBytes() }
            }
        } catch (_: Exception) {
            return emptyMap()
        }
        return files
    }
}
