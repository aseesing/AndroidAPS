// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
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
 * The language is math only, so a design can never hang the watch or reach anything else:
 * - numbers, `pi`, and the variables the layer offers (see [LayerVariables])
 * - `+ - * / %` (`%` is [mod]), `< <= > >= == !=` (1 when true, else 0), `&& || !`, brackets
 * - the functions in [FUNCTIONS], for example `sin`, `clamp`, `noise` and `if(c, a, b)`
 *
 * A comparison with a missing value (NaN) is false, so `v_watch_battery <= 20` is false when the
 * watch does not know its battery.
 */
class Formula private constructor(private val root: Node, val text: String) {

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

        /** The most parts (numbers, names, operations) in one formula. */
        const val MAX_NODES = 300

        /** A formula that is always [value]. */
        fun constant(value: Double): Formula = Formula(Const(value), value.toString())

        /**
         * Reads [text]. [names] gives the index of a variable in the array passed to [eval], or null
         * when the name is not known.
         *
         * @throws FormulaException when the text is not a valid formula
         */
        fun parse(text: String, names: (String) -> Int?): Formula {
            if (text.length > MAX_LENGTH) throw FormulaException("formula longer than $MAX_LENGTH characters")
            val parser = Parser(tokenize(text), names)
            val root = parser.expression()
            if (!parser.atEnd()) throw FormulaException("unexpected '${parser.peekText()}' in '$text'")
            if (parser.nodes > MAX_NODES) throw FormulaException("formula has more than $MAX_NODES parts")
            return Formula(root, text)
        }

        /** The names of all functions, with the number of values each takes. */
        val FUNCTIONS: Map<String, IntRange> = mapOf(
            "sin" to 1..1, "cos" to 1..1, "tan" to 1..1, "atan2" to 2..2, "abs" to 1..1, "sign" to 1..1,
            "sqrt" to 1..1, "exp" to 1..1, "log" to 1..1, "pow" to 2..2,
            "floor" to 1..1, "ceil" to 1..1, "round" to 1..1, "fract" to 1..1, "mod" to 2..2,
            "min" to 2..8, "max" to 2..8, "clamp" to 3..3, "mix" to 3..3, "step" to 2..2, "smoothstep" to 3..3,
            "if" to 3..3, "noise" to 1..2, "hash" to 1..3,
            "easeInOut" to 1..1, "settle" to 1..1, "pulse" to 3..3
        )

        // ---- the functions, also used by the presets and the tests ----

        /** Like `%`, but never negative for a positive [b]: `mod(-1, 4)` is 3. */
        fun mod(a: Double, b: Double): Double = if (b == 0.0) Double.NaN else a - b * floor(a / b)

        /** Slow at the start and the end: 0 at 0, 1 at 1. */
        fun easeInOut(u: Double): Double = 0.5 - 0.5 * cos(PI * u.coerceIn(0.0, 1.0))

        /**
         * From 0 to 1 like a damped spring: past 1 by about a fifth, a smaller swing back, and exactly 1
         * at the end, so a hand stops on its value without a jump.
         */
        fun settle(u: Double): Double {
            val x = u.coerceIn(0.0, 1.0)
            val spring = 1 - exp(-5 * x) * cos(3 * PI * x)
            // The spring is not quite 1 at the end; pull the rest in over the last part
            val rest = (1 - exp(-5.0) * cos(3 * PI)) - 1
            return spring - rest * x
        }

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

        /**
         * The work of each function, looked up once when a formula is read, so working it out costs no
         * search by name. None of these makes an object.
         */
        private val IMPLEMENTATIONS: Map<String, (DoubleArray) -> Double> = mapOf(
            "sin" to { a -> sin(a[0]) },
            "cos" to { a -> cos(a[0]) },
            "tan" to { a -> tan(a[0]) },
            "atan2" to { a -> atan2(a[0], a[1]) },
            "abs" to { a -> abs(a[0]) },
            "sign" to { a -> sign(a[0]) },
            "sqrt" to { a -> sqrt(a[0]) },
            "exp" to { a -> exp(a[0]) },
            "log" to { a -> ln(a[0]) },
            "pow" to { a -> a[0].pow(a[1]) },
            "floor" to { a -> floor(a[0]) },
            "ceil" to { a -> ceil(a[0]) },
            // A half goes up, like a person rounds (kotlin.math.round would make round(2.5) = 2)
            "round" to { a -> floor(a[0] + 0.5) },
            "fract" to { a -> a[0] - floor(a[0]) },
            "mod" to { a -> mod(a[0], a[1]) },
            "min" to { a ->
                var m = a[0]
                for (i in 1 until a.size) m = min(m, a[i])
                m
            },
            "max" to { a ->
                var m = a[0]
                for (i in 1 until a.size) m = max(m, a[i])
                m
            },
            "clamp" to { a -> a[0].coerceIn(min(a[1], a[2]), max(a[1], a[2])) },
            "mix" to { a -> a[0] + (a[1] - a[0]) * a[2] },
            "step" to { a -> if (a[1] >= a[0]) 1.0 else 0.0 },
            "smoothstep" to { a ->
                if (a[0] == a[1]) {
                    // Both edges the same: a hard step, not 0 / 0
                    if (a[2] >= a[0]) 1.0 else 0.0
                } else {
                    val u = ((a[2] - a[0]) / (a[1] - a[0])).coerceIn(0.0, 1.0)
                    u * u * (3 - 2 * u)
                }
            },
            "noise" to { a -> if (a.size == 1) noise(a[0]) else noise(a[0], a[1]) },
            "hash" to { a ->
                when (a.size) {
                    1    -> hash(a[0])
                    2    -> hash(a[0], a[1])
                    else -> hash(a[0], a[1], a[2])
                }
            },
            "easeInOut" to { a -> easeInOut(a[0]) },
            "settle" to { a -> settle(a[0]) },
            "pulse" to { a -> PulseCurve.value(a[0].toLong(), a[1].toInt(), a[2].toFloat()).toDouble() }
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

        /** Kinds: 'n' number, 'i' name, 'o' operator, '(' ')' ',' */
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

                    c == '(' || c == ')' || c == ','                                -> {
                        tokens += Token(c, c.toString()); i++
                    }

                    else                                                            -> {
                        val two = if (i + 1 < text.length) text.substring(i, i + 2) else ""
                        val op = when {
                            two in setOf("<=", ">=", "==", "!=", "&&", "||") -> two
                            c in "+-*/%<>!"                                  -> c.toString()
                            else                                             -> throw FormulaException("unexpected '$c' in '$text'")
                        }
                        tokens += Token('o', op)
                        i += op.length
                    }
                }
            }
            return tokens
        }

        private class Parser(private val tokens: List<Token>, private val names: (String) -> Int?) {

            private var pos = 0
            var nodes = 0
                private set
            private var depth = 0

            fun atEnd() = pos >= tokens.size
            fun peekText() = tokens.getOrNull(pos)?.text ?: "end"
            private fun isOp(vararg ops: String) = pos < tokens.size && tokens[pos].kind == 'o' && tokens[pos].text in ops
            private fun isKind(kind: Char) = pos < tokens.size && tokens[pos].kind == kind
            private fun expect(kind: Char) {
                if (!isKind(kind)) throw FormulaException("expected '$kind' but found '${peekText()}'")
                pos++
            }

            private fun count(node: Node): Node {
                if (++nodes > MAX_NODES) throw FormulaException("formula has more than $MAX_NODES parts")
                return node
            }

            fun expression(): Node {
                if (++depth > 40) throw FormulaException("formula is nested too deep")
                val node = or()
                depth--
                return node
            }

            private fun or(): Node {
                var left = and()
                while (isOp("||")) {
                    pos++; left = binary("||", left, and())
                }
                return left
            }

            private fun and(): Node {
                var left = equality()
                while (isOp("&&")) {
                    pos++; left = binary("&&", left, equality())
                }
                return left
            }

            private fun equality(): Node {
                var left = comparison()
                while (isOp("==", "!=")) {
                    val op = tokens[pos++].text; left = binary(op, left, comparison())
                }
                return left
            }

            private fun comparison(): Node {
                var left = additive()
                while (isOp("<", "<=", ">", ">=")) {
                    val op = tokens[pos++].text; left = binary(op, left, additive())
                }
                return left
            }

            private fun additive(): Node {
                var left = multiplicative()
                while (isOp("+", "-")) {
                    val op = tokens[pos++].text; left = binary(op, left, multiplicative())
                }
                return left
            }

            private fun multiplicative(): Node {
                var left = unary()
                while (isOp("*", "/", "%")) {
                    val op = tokens[pos++].text; left = binary(op, left, unary())
                }
                return left
            }

            private fun unary(): Node {
                if (isOp("-")) {
                    pos++; return fold(count(Negate(unary())))
                }
                if (isOp("+")) {
                    pos++; return unary()
                }
                if (isOp("!")) {
                    pos++; return fold(count(Not(unary())))
                }
                return primary()
            }

            private fun primary(): Node {
                val token = tokens.getOrNull(pos) ?: throw FormulaException("formula ends too early")
                return when (token.kind) {
                    'n'  -> {
                        pos++; count(Const(token.number))
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
                        if (token.text == "pi") return count(Const(PI))
                        val index = names(token.text) ?: throw FormulaException("unknown name '${token.text}'")
                        count(Var(index))
                    }

                    else -> throw FormulaException("unexpected '${token.text}'")
                }
            }

            private fun call(name: String): Node {
                val arity = FUNCTIONS[name] ?: throw FormulaException("unknown function '$name'")
                expect('(')
                val args = ArrayList<Node>()
                if (!isKind(')')) {
                    args += expression()
                    while (isKind(',')) {
                        pos++; args += expression()
                    }
                }
                expect(')')
                if (args.size !in arity) throw FormulaException("'$name' takes ${arity.first}..${arity.last} values, not ${args.size}")
                val node = if (name == "if") If(args[0], args[1], args[2]) else Call(name, args.toTypedArray())
                return fold(count(node))
            }

            private fun binary(op: String, left: Node, right: Node): Node = fold(count(Binary(op, left, right)))

            /** A part made only of fixed numbers is worked out once, here. */
            private fun fold(node: Node): Node = if (node.children().all { it is Const }) Const(node.eval(DoubleArray(0))) else node
        }

        // ---- the parts of a formula ----

        private abstract class Node {

            abstract fun eval(v: DoubleArray): Double
            open fun children(): List<Node> = emptyList()
        }

        private class Const(val value: Double) : Node() {

            override fun eval(v: DoubleArray) = value
        }

        private class Var(val index: Int) : Node() {

            override fun eval(v: DoubleArray) = v[index]
        }

        private class Negate(val a: Node) : Node() {

            override fun eval(v: DoubleArray) = -a.eval(v)
            override fun children() = listOf(a)
        }

        private class Not(val a: Node) : Node() {

            override fun eval(v: DoubleArray) = if (truth(a.eval(v))) 0.0 else 1.0
            override fun children() = listOf(a)
        }

        private class If(val c: Node, val a: Node, val b: Node) : Node() {

            override fun eval(v: DoubleArray): Double {
                val cond = c.eval(v)
                return if (cond != 0.0 && !cond.isNaN()) a.eval(v) else b.eval(v)
            }

            override fun children() = listOf(c, a, b)
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

            override fun children() = listOf(a, b)
        }

        private class Call(name: String, val args: Array<Node>) : Node() {

            private val work = IMPLEMENTATIONS[name] ?: throw IllegalStateException(name)
            private val values = DoubleArray(args.size)
            override fun eval(v: DoubleArray): Double {
                // A fresh array per call is not needed: a formula is only worked out on one thread,
                // and a node never holds itself, so no call can overwrite the values of its own caller
                for (i in args.indices) values[i] = args[i].eval(v)
                return work(values)
            }

            override fun children() = args.toList()
        }

        private fun truth(x: Double) = x != 0.0 && !x.isNaN()
        private fun bool(b: Boolean) = if (b) 1.0 else 0.0
    }
}
