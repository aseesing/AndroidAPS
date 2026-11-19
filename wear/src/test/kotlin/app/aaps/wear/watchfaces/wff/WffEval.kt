// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.wff

import kotlin.math.floor

/**
 * Works out a WFF expression the way the WFF runtime does, for the tests: only the part of WFF
 * that the converter writes. The meanings were measured on the Wear OS 6 emulator (design doc
 * 11.1): `%` keeps the sign of the left side, `x / 0` is 0, `fract` cuts towards 0, `round` is
 * half up, a compare gives 1 or 0. This is our reading of WFF; the emulator probe face is the check
 * against the real runtime.
 */
internal class WffEval(private val sources: Map<String, Double>) {

    private lateinit var tokens: List<String>
    private var pos = 0

    fun eval(text: String): Double {
        tokens = tokenize(text)
        pos = 0
        val value = conditional()
        check(pos == tokens.size) { "unexpected '${tokens.getOrNull(pos)}' in $text" }
        return value
    }

    private fun tokenize(text: String): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c.isWhitespace()                -> i++
                c.isDigit() || c == '.'         -> {
                    val s = i
                    while (i < text.length && (text[i].isDigit() || text[i] == '.')) i++
                    out += text.substring(s, i)
                }

                c.isLetter()                    -> {
                    val s = i
                    while (i < text.length && (text[i].isLetterOrDigit() || text[i] == '_')) i++
                    out += text.substring(s, i)
                }

                c == '['                        -> {
                    val e = text.indexOf(']', i)
                    out += text.substring(i, e + 1)
                    i = e + 1
                }

                else                            -> {
                    val two = text.substring(i, minOf(i + 2, text.length))
                    val op = if (two in setOf("<=", ">=", "==", "!=", "&&", "||")) two else c.toString()
                    out += op
                    i += op.length
                }
            }
        }
        return out
    }

    private fun peek() = tokens.getOrNull(pos)
    private fun take(t: String): Boolean = (peek() == t).also { if (it) pos++ }
    private fun truth(x: Double) = x != 0.0
    private fun b(x: Boolean) = if (x) 1.0 else 0.0

    private fun conditional(): Double {
        val c = or()
        if (!take("?")) return c
        val a = conditional()
        check(take(":"))
        val e = conditional()
        return if (truth(c)) a else e
    }

    private fun or(): Double {
        var l = and()
        while (take("||")) l = b(truth(l) or truth(and()))
        return l
    }

    private fun and(): Double {
        var l = eq()
        while (take("&&")) l = b(truth(l) and truth(eq()))
        return l
    }

    private fun eq(): Double {
        var l = cmp()
        while (true) l = when {
            take("==") -> b(l == cmp())
            take("!=") -> b(l != cmp())
            else       -> return l
        }
    }

    private fun cmp(): Double {
        var l = add()
        while (true) l = when {
            take("<=") -> b(l <= add())
            take(">=") -> b(l >= add())
            take("<")  -> b(l < add())
            take(">")  -> b(l > add())
            else       -> return l
        }
    }

    private fun add(): Double {
        var l = mul()
        while (true) l = when {
            take("+") -> l + mul()
            take("-") -> l - mul()
            else      -> return l
        }
    }

    private fun mul(): Double {
        var l = unary()
        while (true) l = when {
            take("*") -> l * unary()
            take("/") -> unary().let { r -> if (r == 0.0) 0.0 else l / r }
            take("%") -> l % unary()
            else      -> return l
        }
    }

    private fun unary(): Double = when {
        take("-") -> -unary()
        take("!") -> b(!truth(unary()))
        else      -> primary()
    }

    private fun primary(): Double {
        val t = peek() ?: error("ends too early")
        pos++
        return when {
            t == "("                     -> conditional().also { check(take(")")) }
            t.startsWith("[")            -> sources[t.removeSurrounding("[", "]")] ?: error("unknown source $t")
            t[0].isDigit() || t[0] == '.' -> t.toDouble()
            else                         -> call(t)
        }
    }

    private fun call(name: String): Double {
        check(take("("))
        val a = ArrayList<Double>()
        if (!take(")")) {
            do a += conditional() while (take(","))
            check(take(")"))
        }
        return when (name) {
            "sin"   -> Math.sin(a[0])
            "cos"   -> Math.cos(a[0])
            "tan"   -> Math.tan(a[0])
            "asin"  -> Math.asin(a[0])
            "acos"  -> Math.acos(a[0])
            "atan"  -> Math.atan(a[0])
            "abs"   -> Math.abs(a[0])
            "sqrt"  -> Math.sqrt(a[0])
            "exp"   -> Math.exp(a[0])
            "log"   -> Math.log(a[0])
            "pow"   -> Math.pow(a[0], a[1])
            "floor" -> floor(a[0])
            "ceil"  -> Math.ceil(a[0])
            "round" -> Math.round(a[0]).toDouble()
            "log2"  -> Math.log10(a[0]) / Math.log10(2.0)
            "log10" -> Math.log10(a[0])
            "cbrt"  -> Math.cbrt(a[0])
            "expm1" -> Math.expm1(a[0])
            "deg"   -> Math.toDegrees(a[0])
            "rad"   -> Math.toRadians(a[0])
            "fract" -> a[0] - a[0].toLong()
            "clamp" -> a[0].coerceIn(a[1], a[2])
            else    -> error("unknown function $name")
        }
    }
}
