// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

/**
 * The names a formula of a layer may use, and where their values are kept. One table per animation:
 * the `v_` names are added as the formulas use them, the `let` names as they are declared.
 *
 * - `t` seconds since the wake started, `ms` the same in milliseconds, `len` the length of this wake in ms
 * - `env` 0 to 1: rises at the start of a wake and falls at its end (see [WakeAnimationSpec.fadeMs])
 * - `i`, `n`, `u` the copy (see [LayerCopies]); `px`, `py` its point and `p0` to `p7` the extra
 *   numbers of the point; `r1` to `r8` its random numbers
 * - `bpm` the heart rate, `sec`, `min`, `hour` the time of day
 * - `rot0`, `dx0` the own turn and move of a view, `cx0`, `cy0` the middle where the design puts it
 *   (see [LayerSource.View])
 * - `v_<key>` the value of a value key, for example `v_watch_battery`; NaN when there is none.
 *   Read once at the start of a wake.
 * - the names of the `let` of the animation (per frame) and of the layer (per copy)
 *
 * Each variable has a [rate]: how often its value can change, so a formula is worked out only that
 * often (see [Rate]).
 */
class LayerVariables {

    /** How often a value can change. A formula changes as often as the fastest variable it reads. */
    object Rate {

        /** A fixed number. */
        const val CONSTANT = 1

        /** The same for a whole wake: `len`, `bpm` and the value keys. */
        const val WAKE = 2

        /** Changes with the time of the frame (`t`, `ms`, `env`, `sec`, `min`, `hour`), the same for every copy. */
        const val FRAME = 3

        /** Changes from copy to copy: `i`, `n`, `u`, `px`, `py`, `p0`.., `r1`.., and the own place of a view. */
        const val COPY = 4
    }

    private val names = HashMap<String, Int>().apply { FIXED.forEachIndexed { index, name -> put(name, index) } }
    private val rates = ArrayList<Int>().apply { FIXED.forEach { add(rateOfFixed(it)) } }

    /** Per variable: true when it changes with the time of the frame (also through a `let`). */
    private val timed = ArrayList<Boolean>().apply { FIXED.forEach { add(rateOfFixed(it) == Rate.FRAME) } }

    /** The value keys used, and the index of each, in the order they were first used. */
    val valueKeys = ArrayList<String>()
    val valueIndices = ArrayList<Int>()

    /**
     * The index of [name] for a formula of the layer whose own `let` names are [scope] (name to index),
     * or null when a formula may not use it.
     */
    fun index(name: String, scope: Map<String, Int> = emptyMap()): Int? =
        scope[name] ?: names[name] ?: if (name.startsWith(VALUE_PREFIX) && name.length > VALUE_PREFIX.length && valueKeys.size < MAX_VALUES) {
            val index = add(Rate.WAKE, false)
            names[name] = index
            valueKeys += name.removePrefix(VALUE_PREFIX)
            valueIndices += index
            index
        } else null

    /**
     * Forgets the value keys added after the first [count], for a layer that could not be read, so its
     * values are not read for nothing. Their room stays unused.
     */
    fun forgetValueKeysFrom(count: Int) {
        while (valueKeys.size > count) {
            names.remove(VALUE_PREFIX + valueKeys.removeAt(valueKeys.size - 1))
            valueIndices.removeAt(valueIndices.size - 1)
        }
    }

    /**
     * Makes room for a `let` of the animation called [name], with the [rate] of its formula; its name is
     * then known to every layer. False when the name is already taken.
     */
    fun addShared(name: String, formula: Formula): Int? {
        if (name in names || !isName(name)) return null
        return add(rateOf(formula), readsTime(formula)).also { names[name] = it }
    }

    /** Makes room for a `let` of one layer, worked out by [formula]. Its name is kept by the layer. */
    fun addLocal(formula: Formula): Int = add(rateOf(formula), readsTime(formula))

    /** True when [formula] changes with the time of the frame: it reads `t`, `ms`, `env`, the clock, or a `let` that does. */
    fun readsTime(formula: Formula): Boolean = formula.variables.any { timed.getOrElse(it) { true } }

    /** How often the variable [index] can change, see [Rate]. */
    fun rate(index: Int): Int = rates[index]

    /** How often [formula] can change: as often as the fastest variable it reads. */
    fun rateOf(formula: Formula): Int {
        var rate = Rate.CONSTANT
        for (index in formula.variables) rate = maxOf(rate, rates.getOrElse(index) { Rate.COPY })
        return rate
    }

    /** Room for every variable known now. */
    val size: Int get() = rates.size

    private fun add(rate: Int, isTimed: Boolean): Int {
        rates += rate
        timed += isTimed
        return rates.size - 1
    }

    companion object {

        const val VALUE_PREFIX = "v_"
        const val MAX_VALUES = 16

        /** The most extra numbers of a point: `p0` to `p7`. */
        const val EXTRAS = 8

        val FIXED = listOf(
            "t", "ms", "len", "env", "i", "n", "u", "px", "py", "r1", "r2", "r3", "r4", "r5", "r6", "r7", "r8", "bpm", "sec", "min", "hour",
            "rot0", "dx0", "cx0", "cy0", "p0", "p1", "p2", "p3", "p4", "p5", "p6", "p7"
        )
        const val T = 0
        const val MS = 1
        const val LEN = 2
        const val ENV = 3
        const val I = 4
        const val N = 5
        const val U = 6
        const val PX = 7
        const val PY = 8
        const val R1 = 9
        const val RANDOMS = 8
        const val BPM = 17
        const val SEC = 18
        const val MIN = 19
        const val HOUR = 20
        const val ROT0 = 21
        const val DX0 = 22
        const val CX0 = 23
        const val CY0 = 24
        const val P0 = 25

        /** True when [name] can be the name of a `let`: letters, digits and `_`, not starting with a digit, and not a fixed name, `pi` or a value. */
        fun isName(name: String): Boolean =
            name.isNotEmpty() && (name[0].isLetter() || name[0] == '_') && name.all { it.isLetterOrDigit() || it == '_' } &&
                name != "pi" && name !in FIXED && !name.startsWith(VALUE_PREFIX) && name !in Formula.FUNCTIONS

        private fun rateOfFixed(name: String): Int = when (name) {
            "t", "ms", "env", "sec", "min", "hour" -> Rate.FRAME
            "len", "bpm"                           -> Rate.WAKE
            else                                   -> Rate.COPY
        }
    }
}
