package app.aaps.wear.watchfaces.animation

/**
 * A formula that gives a text, for example `clockHour() & ':' & clockMinute()`: the `text` of a text
 * layer. The same language as [Formula]; a formula that gives a number is written as a text like a
 * spreadsheet does by itself.
 */
class TextFormula private constructor(private val root: TextNode, val text: String) {

    /** The index of every variable the formula reads, each once, in rising order; empty when it reads none. */
    val variables: IntArray = root.indices(HashSet()).sorted().toIntArray()

    /** True when the formula reads the [FormulaSetting]: its text can change from wake to wake. */
    val readsSetting: Boolean = root.readsSetting()

    /** True when the formula is one fixed text. */
    val isConstant: Boolean get() = root is TextConst

    /**
     * The text for the variables in [vars]. The same object as last time when no part of the formula
     * changed, so a caller can tell a new text by `!==`.
     */
    fun eval(vars: DoubleArray): String = root.text(vars)

    override fun toString(): String = text

    companion object {

        /**
         * Reads [text], like [Formula.parse].
         *
         * @throws FormulaException when the text is not a valid formula
         */
        fun parse(
            text: String, constants: (String) -> Double? = { null }, setting: FormulaSetting = FormulaSetting(), functions: DesignFunctions? = null,
            names: (String) -> Int?
        ): TextFormula {
            val root = try {
                Formula.read(text, constants, setting, functions, names)
            } catch (e: FormulaException) {
                // The most likely mistake in a text: a fixed text without its quotes
                throw if (e.message?.startsWith("unknown name") == true) FormulaException("${e.message} (a fixed text needs quotes)") else e
            }
            return TextFormula(TextFunctions(setting, names).asText(root), text)
        }
    }
}

/**
 * The text of a [TextFormula], worked out only when an input changed. It keeps the values of the
 * variables the formula reads and the text they gave, for each of [slots] (one per copy of a layer
 * whose text differs per copy, else one). The same values again give the kept text: no work and no
 * new object.
 */
class KeptText(private val formula: TextFormula, slots: Int, private val setting: FormulaSetting) {

    private val variables = formula.variables
    private val inputs = DoubleArray(slots * variables.size)
    private val texts = arrayOfNulls<String>(slots)
    private val generations = IntArray(slots) { -1 }

    /** How many times the formula was worked out, for the tests. */
    var evals = 0
        private set

    /** The text of [slot] for the variables in [values]. */
    fun text(values: DoubleArray, slot: Int): String {
        var same = generations[slot] == setting.generation
        val base = slot * variables.size
        for (k in variables.indices) {
            val x = values[variables[k]]
            if (same && x.toRawBits() != inputs[base + k].toRawBits()) same = false
            inputs[base + k] = x
        }
        texts[slot]?.let { if (same) return it }
        generations[slot] = setting.generation
        evals++
        return formula.eval(values).also { texts[slot] = it }
    }
}
