// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.utils

import android.graphics.Bitmap

/**
 * A picture the app draws itself, for a design that asks for it by name, like the moon as it is now.
 * The same picture is right for [refreshMs] and for the same [inputs]; see [DynamicImages].
 */
interface DynamicImage {

    /** How long one picture stays right, in ms. A new picture is drawn when the time crosses a step of this length. */
    val refreshMs: Long

    /** What the picture depends on besides the time, like the place of the wearer; compared with `equals`. */
    fun inputs(): Any? = null

    /** The picture at [epochMs], in a square of [size] pixels; null when it cannot be made. */
    fun draw(epochMs: Long, size: Int): Bitmap?
}

/**
 * Keeps the pictures of the [DynamicImage]s of one watch face, one per name and size, and draws a
 * picture again only when its time step or its inputs changed. A view and a layer that show the same
 * picture at the same size share it.
 *
 * A picture that is replaced is not freed here: a view may still show it until its next style pass,
 * and two replacements can come before that. The garbage collector frees it when nobody holds it any
 * more (bitmap pixels live on the Java heap since API 26). Only [release] frees pictures.
 *
 * @param images the pictures by name, for example `moon`
 */
class DynamicImages(private val images: Map<String, DynamicImage>) {

    private class Entry(val name: String, val size: Int) {

        var step = Long.MIN_VALUE
        var inputs: Any? = null
        var bitmap: Bitmap? = null
    }

    /** The kept pictures, the one used last at the end. */
    private val entries = ArrayList<Entry>()

    /** How often a picture was drawn, for tests. */
    var draws = 0
        private set

    /** The picture [name] at [epochMs] in a square of [size] pixels; null when there is no such picture or it cannot be made. */
    fun bitmap(name: String, epochMs: Long, size: Int): Bitmap? = entry(name, epochMs, size)?.bitmap

    /** Frees every picture. Only when nothing shows them any more, like when the face goes. */
    fun release() {
        for (entry in entries) entry.bitmap?.recycle()
        entries.clear()
    }

    private fun entry(name: String, epochMs: Long, size: Int): Entry? {
        if (size <= 0) return null
        val image = images[name] ?: return null
        val entry = find(name, size)
        val step = Math.floorDiv(epochMs, image.refreshMs)
        val inputs = image.inputs()
        if (step != entry.step || inputs != entry.inputs || entry.bitmap?.isRecycled == true) {
            val made = runCatching { image.draw(epochMs, size) }.getOrNull()
            draws++
            entry.bitmap = made
            entry.step = step
            entry.inputs = inputs
        }
        return entry
    }

    /** The entry of [name] at [size], made when there is none; the oldest one is let go when there are too many. */
    private fun find(name: String, size: Int): Entry {
        for (i in entries.indices) {
            val entry = entries[i]
            if (entry.size == size && entry.name == name) {
                if (i != entries.lastIndex) {
                    entries.removeAt(i)
                    entries.add(entry)
                }
                return entry
            }
        }
        // Not freed: a view could still show it. A size changes seldom (the preview on another screen).
        if (entries.size >= MAX_ENTRIES) entries.removeAt(0)
        return Entry(name, size).also { entries.add(it) }
    }

    private companion object {

        /** The most pictures kept at once: a few sizes of a few pictures. */
        const val MAX_ENTRIES = 6
    }
}
