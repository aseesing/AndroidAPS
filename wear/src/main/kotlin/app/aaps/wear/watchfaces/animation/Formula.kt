package app.aaps.wear.watchfaces.animation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** A formula of a design that cannot be read: the text says why. */
class FormulaException(message: String) : IllegalArgumentException(message)

/**
 * A small formula of an animated layer, for example `sin(t * 0.3) * 8`, read once and then worked
 * out for every frame and every copy of the layer.
 *
 * The language is math and texts only, so a design can never hang the watch or reach anything else:
 * - numbers, `pi`, and the variables the layer offers (see [LayerVariables])
 * - `+ - * / %` (`%` is [mod]), `< <= > >= == !=` (1 when true, else 0), `&& || !`, brackets
 * - the functions in [FUNCTIONS], for example `sin`, `clamp`, `noise` and `if(c, a, b)`
 * - texts: `'hello'`, `a & b` (joined), `==` and `!=` of two texts, and the text functions (see
 *   `TextFunctions`), for example `len('abc')`. A formula that gives a text is a [TextFormula].
 * - the own functions of the design (see [DesignFunctions])
 *
 * A comparison with a missing value (NaN) is false, so `v_watch_battery <= 20` is false when the
 * watch does not know its battery.
 */
class Formula private constructor(private val root: Node, val text: String) {

    /**
     * The variables the formula reads, one bit per index of [parse]'s name lookup (bit `1 shl index`).
     * An index of 63 or more sets bit 63. 0 for a formula that reads no variable: it is one fixed
     * number. So a caller can tell which formulas give the same value for every copy, or for a whole
     * wake, and work those out less often.
     */
    val reads: Long = root.reads()

    /** The index of every variable the formula reads, each once, in rising order; empty for a fixed number. */
    val variables: IntArray = root.indices(HashSet()).sorted().toIntArray()

    /** True when the formula reads the [FormulaSetting] (through a text function): its value can change from wake to wake. */
    val readsSetting: Boolean = root.readsSetting()

    /** The value for the variables in [vars], indexed as the name lookup of [parse] gave them. */
    fun eval(vars: DoubleArray): Double = root.eval(vars)

    /** [eval] as a Float. */
    fun evalF(vars: DoubleArray): Float = root.eval(vars).toFloat()

    /** True when the formula is one fixed number. */
    val isConstant: Boolean get() = root is Const

    override fun toString(): String = text

    override fun equals(other: Any?): Boolean = other is Formula && other.text == text
    override fun hashCode(): Int = text.hashCode()

    companion object {

        /** The longest formula text. */
        const val MAX_LENGTH = 600

        /** The most parts (numbers, names, operations) in one formula, with the parts of the own functions it calls. */
        const val MAX_NODES = 300

        /** A formula that is always [value]. */
        fun constant(value: Double): Formula = Formula(Const(value), value.toString())

        /**
         * Reads [text]. [names] gives the index of a variable in the array passed to [eval], or null
         * when the name is not known. [constants] gives the value of a name that is a fixed number (a
         * `let` of fixed numbers only), or null: such a name is folded into the formula like a number.
         *
         * @param setting what the text functions read of the watch
         * @param functions the own functions of the design, or null
         * @throws FormulaException when the text is not a valid formula, or it gives a text
         */
        fun parse(
            text: String, constants: (String) -> Double? = { null }, setting: FormulaSetting = FormulaSetting(), functions: DesignFunctions? = null,
            names: (String) -> Int?
        ): Formula {
            val root = read(text, constants, setting, functions, names) as? Node ?: throw FormulaException("'$text' gives a text, not a number")
            return Formula(root, text)
        }

        /** The names of the number functions, with the number of values each takes. The text functions are in `TextFunctions`. */
        val FUNCTIONS: Map<String, IntRange> = mapOf(
            "sin" to 1..1, "cos" to 1..1, "tan" to 1..1, "atan2" to 2..2, "abs" to 1..1, "sign" to 1..1,
            "sqrt" to 1..1, "exp" to 1..1, "log" to 1..1, "pow" to 2..2,
            "floor" to 1..1, "ceil" to 1..1, "round" to 1..1, "fract" to 1..1, "mod" to 2..2,
            "min" to 2..8, "max" to 2..8, "clamp" to 3..3, "mix" to 3..3, "step" to 2..2, "smoothstep" to 3..3,
            "if" to 3..3, "noise" to 1..2, "hash" to 1..3, "missing" to 1..1,
            "easeInOut" to 1..1,
            "window" to 3..3, "pingpong" to 2..2
        )

        // ---- the functions, also used by the presets and the tests ----

        /** Like `%`, but never negative for a positive [b]: `mod(-1, 4)` is 3. */
        fun mod(a: Double, b: Double): Double = if (b == 0.0) Double.NaN else a - b * floor(a / b)

        /** Slow at the start and the end: 0 at 0, 1 at 1. */
        fun easeInOut(u: Double): Double = 0.5 - 0.5 * cos(PI * u.coerceIn(0.0, 1.0))

        /**
         * A number from 0 to 1 that is always the same for the same values, and looks random. One, two
         * or three values; no `vararg`, so a call makes no array (it runs many times per frame).
         */
        fun hash(a: Double): Double = toUnit(mix64(step(SEED, a)))
        fun hash(a: Double, b: Double): Double = toUnit(mix64(step(step(SEED, a), b)))
        fun hash(a: Double, b: Double, c: Double): Double = toUnit(mix64(step(step(step(SEED, a), b), c)))

        private const val SEED = -0x61c8864680b583ebL
        private fun step(h: Long, v: Double): Long = mix64(h xor java.lang.Double.doubleToLongBits(if (v == 0.0) 0.0 else v))
        private fun toUnit(h: Long): Double = (h ushr 11) * (1.0 / (1L shl 53))

        /**
         * How far [t] is through the time from [start] to [start] + [duration]: 0 before, 1 after, and
         * evenly from 0 to 1 in between. A [duration] of 0 or less is a step at [start].
         */
        fun window(t: Double, start: Double, duration: Double): Double =
            if (duration <= 0.0) (if (t >= start) 1.0 else 0.0) else ((t - start) / duration).coerceIn(0.0, 1.0)

        /**
         * Goes from 0 up to [length] and back down to 0 as [x] runs from 0 to 2 x [length], and again,
         * like a ball between two walls. 0 for a [length] of 0 or less.
         */
        fun pingpong(x: Double, length: Double): Double = if (length > 0.0) length - abs(mod(x, 2 * length) - length) else 0.0

        /** Smooth random from 0 to 1 along [x]: the same x always gives the same value. */
        fun noise(x: Double): Double {
            val i = floor(x)
            val f = x - i
            val u = f * f * (3 - 2 * f)
            return hash(i) * (1 - u) + hash(i + 1) * u
        }

        /** Smooth random from 0 to 1 over the plane. */
        fun noise(x: Double, y: Double): Double {
            val ix = floor(x)
            val iy = floor(y)
            val fx = x - ix
            val fy = y - iy
            val ux = fx * fx * (3 - 2 * fx)
            val uy = fy * fy * (3 - 2 * fy)
            val a = hash(ix, iy) * (1 - ux) + hash(ix + 1, iy) * ux
            val b = hash(ix, iy + 1) * (1 - ux) + hash(ix + 1, iy + 1) * ux
            return a * (1 - uy) + b * uy
        }

        private fun mix64(z0: Long): Long {
            var z = z0
            z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
            z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
            return z xor (z ushr 31)
        }

        // The work of each function, by the number of values it is given, looked up once when a
        // formula is read, so working it out costs no search by name. Each is a `fun interface` that
        // takes and gives plain numbers: a Kotlin function type would box every result, and these run
        // many times per frame. None of them makes an object.

        private fun interface Fn1 {

            fun apply(a: Double): Double
        }

        private fun interface Fn2 {

            fun apply(a: Double, b: Double): Double
        }

        private fun interface Fn3 {

            fun apply(a: Double, b: Double, c: Double): Double
        }

        /** For the functions that take more than three values: [a] holds them. */
        private fun interface FnN {

            fun apply(a: DoubleArray): Double
        }

        private val FN1: Map<String, Fn1> = mapOf(
            "sin" to Fn1 { a -> sin(a) },
            "cos" to Fn1 { a -> cos(a) },
            "tan" to Fn1 { a -> tan(a) },
            "abs" to Fn1 { a -> abs(a) },
            "sign" to Fn1 { a -> sign(a) },
            "sqrt" to Fn1 { a -> sqrt(a) },
            "exp" to Fn1 { a -> exp(a) },
            "log" to Fn1 { a -> ln(a) },
            "floor" to Fn1 { a -> floor(a) },
            "ceil" to Fn1 { a -> ceil(a) },
            // A half goes up, like a person rounds (kotlin.math.round would make round(2.5) = 2)
            "round" to Fn1 { a -> floor(a + 0.5) },
            "fract" to Fn1 { a -> a - floor(a) },
            "noise" to Fn1 { a -> noise(a) },
            "hash" to Fn1 { a -> hash(a) },
            "easeInOut" to Fn1 { a -> easeInOut(a) },
            "missing" to Fn1 { a -> if (a.isNaN()) 1.0 else 0.0 }
        )

        private val FN2: Map<String, Fn2> = mapOf(
            "atan2" to Fn2 { a, b -> atan2(a, b) },
            "pow" to Fn2 { a, b -> a.pow(b) },
            "mod" to Fn2 { a, b -> mod(a, b) },
            "min" to Fn2 { a, b -> min(a, b) },
            "max" to Fn2 { a, b -> max(a, b) },
            "step" to Fn2 { a, b -> if (b >= a) 1.0 else 0.0 },
            "noise" to Fn2 { a, b -> noise(a, b) },
            "hash" to Fn2 { a, b -> hash(a, b) },
            "pingpong" to Fn2 { a, b -> pingpong(a, b) }
        )

        private val FN3: Map<String, Fn3> = mapOf(
            "min" to Fn3 { a, b, c -> min(min(a, b), c) },
            "max" to Fn3 { a, b, c -> max(max(a, b), c) },
            "clamp" to Fn3 { a, b, c -> a.coerceIn(min(b, c), max(b, c)) },
            "mix" to Fn3 { a, b, c -> a + (b - a) * c },
            "smoothstep" to Fn3 { a, b, c ->
                if (a == b) {
                    // Both edges the same: a hard step, not 0 / 0
                    if (c >= a) 1.0 else 0.0
                } else {
                    val u = ((c - a) / (b - a)).coerceIn(0.0, 1.0)
                    u * u * (3 - 2 * u)
                }
            },
            "hash" to Fn3 { a, b, c -> hash(a, b, c) },
            "window" to Fn3 { a, b, c -> window(a, b, c) }
        )

        private val FNN: Map<String, FnN> = mapOf(
            "min" to FnN { a ->
                var m = a[0]
                for (i in 1 until a.size) m = min(m, a[i])
                m
            },
            "max" to FnN { a ->
                var m = a[0]
                for (i in 1 until a.size) m = max(m, a[i])
                m
            }
        )

        // The operators of [Binary], as numbers, so working one out is a jump and not a text compare
        private const val OP_AND = 0
        private const val OP_OR = 1
        private const val OP_ADD = 2
        private const val OP_SUB = 3
        private const val OP_MUL = 4
        private const val OP_DIV = 5
        private const val OP_MOD = 6
        private const val OP_LT = 7
        private const val OP_LE = 8
        private const val OP_GT = 9
        private const val OP_GE = 10
        private const val OP_EQ = 11
        private const val OP_NE = 12
        private val OPERATORS = mapOf(
            "&&" to OP_AND, "||" to OP_OR, "+" to OP_ADD, "-" to OP_SUB, "*" to OP_MUL, "/" to OP_DIV, "%" to OP_MOD,
            "<" to OP_LT, "<=" to OP_LE, ">" to OP_GT, ">=" to OP_GE, "==" to OP_EQ, "!=" to OP_NE
        )

        // ---- reading ----

        private class Token(val kind: Char, val text: String, val number: Double = 0.0)

        /** Kinds: 'n' number, 'i' name, 's' text, 'o' operator, '(' ')' ',' */
        private fun tokenize(text: String): List<Token> {
            val tokens = ArrayList<Token>()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when {
                    c.isWhitespace()                                                -> i++
                    c.isDigit() || (c == '.' && i + 1 < text.length && text[i + 1].isDigit()) -> {
                        val start = i
                        while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                        if (i < text.length && (text[i] == 'e' || text[i] == 'E')) {
                            i++
                            if (i < text.length && (text[i] == '+' || text[i] == '-')) i++
                            while (i < text.length && text[i].isDigit()) i++
                        }
                        val s = text.substring(start, i)
                        tokens += Token('n', s, s.toDoubleOrNull() ?: throw FormulaException("bad number '$s'"))
                    }

                    c.isLetter() || c == '_'                                        -> {
                        val start = i
                        while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                        tokens += Token('i', text.substring(start, i))
                    }

                    c == '\'' || c == '"'                                           -> {
                        // A text between two of the same quotes; the quote twice inside it is one quote
                        val content = StringBuilder()
                        i++
                        while (true) {
                            if (i >= text.length) throw FormulaException("a text has no closing $c in '$text'")
                            if (text[i] == c) {
                                if (i + 1 < text.length && text[i + 1] == c) {
                                    content.append(c); i += 2
                                    continue
                                }
                                i++
                                break
                            }
                            content.append(text[i++])
                        }
                        tokens += Token('s', content.toString())
                    }

                    c == '(' || c == ')' || c == ','                                -> {
                        tokens += Token(c, c.toString()); i++
                    }

                    else                                                            -> {
                        val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
                        val op = when {
                            two in setOf("<=", ">=", "==", "!=", "&&", "||") -> two
                            c in "+-*/%<>!&"                                 -> c.toString()
                            else                                             -> throw FormulaException("unexpected '$c' in '$text'")
                        }
                        tokens += Token('o', op)
                        i += op.length
                    }
                }
            }
            return tokens
        }

        /** Counts the parts of a formula, also those of the own functions it calls. */
        private class Counter {

            var nodes = 0
        }

        /**
         * Reads [text] into its parts, a number or a text.
         *
         * @param params the values of the own function whose body [text] is, by name
         * @param using the own functions that are being read now, the outer one first
         */
        private fun read(
            text: String, names: (String) -> Int?, constants: (String) -> Double?, setting: FormulaSetting, functions: DesignFunctions?,
            params: Map<String, Part>, using: List<String>, counter: Counter
        ): Part {
            if (text.length > MAX_LENGTH) throw FormulaException("formula longer than $MAX_LENGTH characters")
            val parser = Parser(tokenize(text), names, constants, setting, functions, params, using, counter)
            val root = parser.expression()
            if (!parser.atEnd()) throw FormulaException("unexpected '${parser.peekText()}' in '$text'")
            return root
        }

        /** The parts of the formula [text], for [parse] and [TextFormula.parse]. */
        internal fun read(text: String, constants: (String) -> Double?, setting: FormulaSetting, functions: DesignFunctions?, names: (String) -> Int?): Part =
            read(text, names, constants, setting, functions, emptyMap(), emptyList(), Counter())

        private class Parser(
            private val tokens: List<Token>, private val names: (String) -> Int?, private val constants: (String) -> Double?,
            private val setting: FormulaSetting, private val functions: DesignFunctions?,
            private val params: Map<String, Part>, private val using: List<String>, private val counter: Counter
        ) {

            private val texts = TextFunctions(setting, names)
            private var pos = 0
            private var depth = 0

            fun atEnd() = pos >= tokens.size
            fun peekText() = tokens.getOrNull(pos)?.text ?: "end"
            private fun isOp(vararg ops: String) = pos < tokens.size && tokens[pos].kind == 'o' && tokens[pos].text in ops
            private fun isKind(kind: Char) = pos < tokens.size && tokens[pos].kind == kind
            private fun expect(kind: Char) {
                if (!isKind(kind)) throw FormulaException("expected '$kind' but found '${peekText()}'")
                pos++
            }

            private fun <P : Part> count(node: P): P {
                if (++counter.nodes > MAX_NODES) throw FormulaException("formula has more than $MAX_NODES parts")
                return node
            }

            /** [part] as a number, for [what]; a text there is a mistake. */
            private fun number(part: Part, what: String): Node =
                part as? Node ?: throw FormulaException("'$what' needs a number, not a text" + if (what == "+") " (join texts with &)" else "")

            fun expression(): Part {
                if (++depth > 40) throw FormulaException("formula is nested too deep")
                val node = or()
                depth--
                return node
            }

            private fun or(): Part {
                var left = and()
                while (isOp("||")) {
                    pos++; left = binary("||", left, and())
                }
                return left
            }

            private fun and(): Part {
                var left = equality()
                while (isOp("&&")) {
                    pos++; left = binary("&&", left, equality())
                }
                return left
            }

            private fun equality(): Part {
                var left = comparison()
                while (isOp("==", "!=")) {
                    val op = tokens[pos++].text
                    val right = comparison()
                    val a = left
                    left = when {
                        a is TextNode && right is TextNode -> fold(count(TextCompare(a, right, equal = op == "==")))
                        a is Node && right is Node         -> binary(op, a, right)
                        else                               -> throw FormulaException("'$op' cannot compare a text with a number")
                    }
                }
                return left
            }

            private fun comparison(): Part {
                var left = joined()
                while (isOp("<", "<=", ">", ">=")) {
                    val op = tokens[pos++].text; left = binary(op, left, joined())
                }
                return left
            }

            /** `a & b`: the two as one text. Like in a spreadsheet, after `+` and `-` and before the compares. */
            private fun joined(): Part {
                var left = additive()
                while (isOp("&")) {
                    pos++
                    left = fold(count(texts.join(arrayOf(texts.asText(left), texts.asText(additive())))))
                }
                return left
            }

            private fun additive(): Part {
                var left = multiplicative()
                while (isOp("+", "-")) {
                    val op = tokens[pos++].text; left = binary(op, left, multiplicative())
                }
                return left
            }

            private fun multiplicative(): Part {
                var left = unary()
                while (isOp("*", "/", "%")) {
                    val op = tokens[pos++].text; left = binary(op, left, unary())
                }
                return left
            }

            private fun unary(): Part {
                if (isOp("-")) {
                    pos++; return fold(count(Negate(number(unary(), "-"))))
                }
                if (isOp("+")) {
                    pos++; return number(unary(), "+")
                }
                if (isOp("!")) {
                    pos++; return fold(count(Not(number(unary(), "!"))))
                }
                return primary()
            }

            private fun primary(): Part {
                val token = tokens.getOrNull(pos) ?: throw FormulaException("formula ends too early")
                return when (token.kind) {
                    'n'  -> {
                        pos++; count(Const(token.number))
                    }

                    's'  -> {
                        pos++; count(TextConst(TextFunctions.cut(token.text)))
                    }

                    '('  -> {
                        pos++
                        val inner = expression()
                        expect(')')
                        inner
                    }

                    'i'  -> {
                        pos++
                        if (isKind('(')) return call(token.text)
                        // A value of the own function this is the body of: the part the caller gave
                        params[token.text]?.let { return count(it) }
                        if (token.text == "pi") return count(Const(PI))
                        constants(token.text)?.let { return count(Const(it)) }
                        val index = names(token.text) ?: throw FormulaException("unknown name '${token.text}'")
                        count(Var(index))
                    }

                    else -> throw FormulaException("unexpected '${token.text}'")
                }
            }

            private fun call(name: String): Part {
                expect('(')
                val args = ArrayList<Part>()
                if (!isKind(')')) {
                    args += expression()
                    while (isKind(',')) {
                        pos++; args += expression()
                    }
                }
                expect(')')
                functions?.get(name)?.let { return own(it, args) }
                texts.call(name, args)?.let { return fold(count(it)) }
                val arity = FUNCTIONS[name] ?: throw FormulaException("unknown function '$name'")
                if (args.size !in arity) throw FormulaException("'$name' takes ${arity.first}..${arity.last} values, not ${args.size}")
                if (name == "if") {
                    val condition = number(args[0], name)
                    val a = args[1]
                    val b = args[2]
                    val choice: Part = when {
                        a is Node && b is Node         -> If(condition, a, b)
                        a is TextNode && b is TextNode -> TextIf(condition, a, b)
                        else                           -> throw FormulaException("'if' needs two numbers or two texts to choose from")
                    }
                    return fold(count(choice))
                }
                return fold(count(callOf(name, args.map { number(it, name) })))
            }

            /** The body of the own function [function] with [args] as its values. */
            private fun own(function: DesignFunction, args: List<Part>): Part {
                val name = function.name
                if (name in using) throw FormulaException("function '$name' uses itself")
                if (using.size >= DesignFunction.MAX_DEPTH) throw FormulaException("functions nested more than ${DesignFunction.MAX_DEPTH} deep")
                if (args.size != function.params.size) throw FormulaException("'$name' takes ${function.params.size} values, not ${args.size}")
                val all = functions ?: throw IllegalStateException(name)
                val values = HashMap<String, Part>()
                function.params.forEachIndexed { index, param -> values[param] = args[index] }
                return try {
                    read(function.body, all.names, all.constants, setting, all, values, using + name, counter)
                } catch (e: FormulaException) {
                    throw FormulaException(if (using.isEmpty()) "in function '$name': ${e.message}" else e.message ?: "")
                }
            }

            private fun binary(op: String, left: Part, right: Part): Part = fold(count(Binary(op, number(left, op), number(right, op))))

            /** A part made only of fixed numbers and texts is worked out once, here; not one that reads the watch setting. */
            private fun fold(node: Part): Part = when {
                node.usesSetting || !node.children().all { it.isFixed } -> node
                node is Node                                            -> Const(node.eval(NO_VALUES))
                node is TextNode                                        -> TextConst(node.text(NO_VALUES))
                else                                                    -> node
            }
        }

        private val NO_VALUES = DoubleArray(0)

        /** The part that works out the function [name] for [args], with the work for that many values. */
        private fun callOf(name: String, args: List<Node>): Node {
            fun missing(): Nothing = throw IllegalStateException("$name/${args.size}")
            return when (args.size) {
                1    -> Call1(FN1[name] ?: missing(), args[0])
                2    -> Call2(FN2[name] ?: missing(), args[0], args[1])
                3    -> Call3(FN3[name] ?: missing(), args[0], args[1], args[2])
                else -> CallN(FNN[name] ?: missing(), args.toTypedArray())
            }
        }

        // ---- the number parts of a formula (the shared ones are in FormulaParts.kt) ----

        private class Negate(val a: Node) : Node() {

            override fun eval(v: DoubleArray) = -a.eval(v)
            override fun children(): List<Part> = listOf(a)
        }

        private class Not(val a: Node) : Node() {

            override fun eval(v: DoubleArray) = if (truth(a.eval(v))) 0.0 else 1.0
            override fun children(): List<Part> = listOf(a)
        }

        private class If(val c: Node, val a: Node, val b: Node) : Node() {

            override fun eval(v: DoubleArray): Double {
                val cond = c.eval(v)
                return if (cond != 0.0 && !cond.isNaN()) a.eval(v) else b.eval(v)
            }

            override fun children(): List<Part> = listOf(c, a, b)
        }

        private class Binary(op: String, val a: Node, val b: Node) : Node() {

            private val code = OPERATORS[op] ?: throw IllegalStateException(op)

            override fun eval(v: DoubleArray): Double {
                val x = a.eval(v)
                return when (code) {
                    OP_AND -> if (truth(x) && truth(b.eval(v))) 1.0 else 0.0
                    OP_OR  -> if (truth(x) || truth(b.eval(v))) 1.0 else 0.0
                    else   -> {
                        val y = b.eval(v)
                        when (code) {
                            OP_ADD -> x + y
                            OP_SUB -> x - y
                            OP_MUL -> x * y
                            OP_DIV -> x / y
                            OP_MOD -> mod(x, y)
                            OP_LT  -> bool(x < y)
                            OP_LE  -> bool(x <= y)
                            OP_GT  -> bool(x > y)
                            OP_GE  -> bool(x >= y)
                            OP_EQ  -> bool(x == y)
                            else   -> bool(x != y && !x.isNaN() && !y.isNaN())
                        }
                    }
                }
            }

            override fun children(): List<Part> = listOf(a, b)
        }

        private class Call1(val work: Fn1, val a: Node) : Node() {

            override fun eval(v: DoubleArray): Double = work.apply(a.eval(v))
            override fun children(): List<Part> = listOf(a)
        }

        private class Call2(val work: Fn2, val a: Node, val b: Node) : Node() {

            override fun eval(v: DoubleArray): Double = work.apply(a.eval(v), b.eval(v))
            override fun children(): List<Part> = listOf(a, b)
        }

        private class Call3(val work: Fn3, val a: Node, val b: Node, val c: Node) : Node() {

            override fun eval(v: DoubleArray): Double = work.apply(a.eval(v), b.eval(v), c.eval(v))
            override fun children(): List<Part> = listOf(a, b, c)
        }

        private class CallN(val work: FnN, val args: Array<Node>) : Node() {

            private val values = DoubleArray(args.size)
            override fun eval(v: DoubleArray): Double {
                // A fresh array per call is not needed: a formula is only worked out on one thread,
                // and a node never holds itself, so no call can overwrite the values of its own caller
                for (i in args.indices) values[i] = args[i].eval(v)
                return work.apply(values)
            }

            override fun children(): List<Part> = args.asList()
        }

        private fun truth(x: Double) = x != 0.0 && !x.isNaN()
        private fun bool(b: Boolean) = if (b) 1.0 else 0.0
    }
}
