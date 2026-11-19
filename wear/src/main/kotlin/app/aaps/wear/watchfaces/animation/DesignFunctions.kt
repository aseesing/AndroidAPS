// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

/**
 * A function a design writes itself, in the `functions` of its `animation`:
 * `"two(x)": "text(x, '00')"`. It gives a number or a text, as its [body] does.
 *
 * @param name the name of the function
 * @param params the names of its values, in order. Inside the body such a name is the value, also
 *   when a variable has the same name (`n`, `t`)
 * @param body the formula; it may use the [params], the fixed variables, the `v_` values, the `let`
 *   of the animation and other functions
 */
data class DesignFunction(val name: String, val params: List<String>, val body: String) {

    companion object {

        /** The most functions of one design. */
        const val MAX_FUNCTIONS = 32

        /** The most values one function takes. */
        const val MAX_PARAMS = 8

        /** How deep functions may call each other. */
        const val MAX_DEPTH = 4

        private val PARAM = Regex("[A-Za-z_][A-Za-z0-9_]*")
        private val HEAD = Regex("""\s*([A-Za-z_][A-Za-z0-9_]*)\s*\(([^()]*)\)\s*""")

        /**
         * The function of the json entry [head] (`"name(a, b)"`) with [body].
         *
         * @throws FormulaException when the head is not a name with its values in brackets, a name is
         *   taken, or the body is too long
         */
        fun read(head: String, body: String): DesignFunction {
            val match = HEAD.matchEntire(head) ?: throw FormulaException("'$head' is not a function head like 'name(a, b)'")
            val name = match.groupValues[1]
            if (!LayerVariables.isName(name)) throw FormulaException("'$name' cannot be the name of a function")
            val params = match.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() || match.groupValues[2].isNotBlank() }
            if (params.size > MAX_PARAMS) throw FormulaException("function '$name' takes more than $MAX_PARAMS values")
            for (param in params) if (!PARAM.matches(param) || params.count { it == param } > 1) throw FormulaException("'$param' cannot be a value of function '$name'")
            if (body.length > Formula.MAX_LENGTH) throw FormulaException("function '$name' is longer than ${Formula.MAX_LENGTH} characters")
            return DesignFunction(name, params, body)
        }
    }
}

/**
 * The own functions of a design, for the formulas that call them. A call is replaced by the body of
 * the function when the formula is read, with the values put in: so a call costs nothing more per
 * frame than the same formula written out, and the formula is worked out as often as its values need.
 *
 * @param functions the functions; a name that comes twice keeps the first
 * @param names the index of a variable for a body: the names every layer knows, not the `let` of one
 *   layer, so a function means the same in every layer
 * @param constants the value of a name that is a fixed number for a body, or null
 */
class DesignFunctions(functions: List<DesignFunction>, internal val names: (String) -> Int?, internal val constants: (String) -> Double? = { null }) {

    private val byName = LinkedHashMap<String, DesignFunction>().apply { functions.take(DesignFunction.MAX_FUNCTIONS).forEach { putIfAbsent(it.name, it) } }

    operator fun get(name: String): DesignFunction? = byName[name]
}
