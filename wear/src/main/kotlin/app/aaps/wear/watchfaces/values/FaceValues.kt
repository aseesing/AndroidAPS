// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.values

/**
 * The one place a design reads a value of the face by its key, for example `sgv` or `watch_battery`:
 * for a formula (`v_<key>`), for the `orbit` of a view and for the layers that stay on the face.
 *
 * The keys are known when the face is made, so a read is one map lookup and one call: no search
 * through all keys, and no object made per read. A reader is made only for a key that is read.
 *
 * Not thread safe: one face reads its values on one thread.
 *
 * @param keys all value keys, in the order of their index; an empty key is left out (it is no value)
 * @param newReader makes the reader of the key with this index, once; a reader gives the value now,
 *   or null when there is none
 */
class FaceValues(keys: List<String>, private val newReader: (Int) -> () -> Double?) {

    private val indexOf: Map<String, Int> = HashMap<String, Int>(keys.size * 2).apply {
        keys.forEachIndexed { index, key -> if (key.isNotEmpty()) putIfAbsent(key, index) }
    }
    private val readers = arrayOfNulls<() -> Double?>(keys.size)

    /** The value of [key] now, or null when the face has no such value or does not know it. */
    fun value(key: String): Double? {
        val index = indexOf[key] ?: return null
        val reader = readers[index] ?: newReader(index).also { readers[index] = it }
        return reader()
    }
}
