// ============================================================================================
// PERSONAL PATCH - not part of upstream AAPS (branch local/personal, see .github/README.md).
// Own file: keep upstream files to small hooks that call into here.
// ============================================================================================
package app.aaps.wear.watchfaces.animation

import kotlin.math.min

/**
 * One part of a formula that was read: a number part ([Node]) or a text part ([TextNode]). The kind
 * of every part is known when the formula is read, so a number is never taken for a text while a
 * frame is drawn.
 */
internal abstract class Part {

    open fun children(): List<Part> = emptyList()

    /** True for a fixed number or a fixed text. */
    open val isFixed: Boolean get() = false

    /**
     * True when the part itself reads the [FormulaSetting] (the language of the watch, 12 or 24 hours,
     * the glucose unit): its result can change without a variable changing, so it is never worked out
     * when the formula is read.
     */
    open val usesSetting: Boolean get() = false

    /** True when this part or a part inside it reads the [FormulaSetting]. */
    fun readsSetting(): Boolean = usesSetting || children().any { it.readsSetting() }

    /** The variables this part reads, see [Formula.reads]. Only used when a formula is read. */
    open fun reads(): Long = children().fold(0L) { mask, child -> mask or child.reads() }

    /** Adds the index of every variable this part reads to [into], see [Formula.variables]. */
    open fun indices(into: MutableSet<Int>): MutableSet<Int> = into.also { children().forEach { child -> child.indices(it) } }
}

/** A part that gives a number. */
internal abstract class Node : Part() {

    abstract fun eval(v: DoubleArray): Double
}

/** A part that gives a text. */
internal abstract class TextNode : Part() {

    abstract fun text(v: DoubleArray): String
}

internal class Const(val value: Double) : Node() {

    override val isFixed: Boolean get() = true
    override fun eval(v: DoubleArray) = value
}

internal class TextConst(val value: String) : TextNode() {

    override val isFixed: Boolean get() = true
    override fun text(v: DoubleArray) = value
}

internal class Var(val index: Int) : Node() {

    override fun eval(v: DoubleArray) = v[index]
    override fun reads(): Long = 1L shl min(index, 63)
    override fun indices(into: MutableSet<Int>): MutableSet<Int> = into.also { it.add(index) }
}

/** `if(c, a, b)` with two texts. */
internal class TextIf(val c: Node, val a: TextNode, val b: TextNode) : TextNode() {

    override fun text(v: DoubleArray): String {
        val cond = c.eval(v)
        return if (cond != 0.0 && !cond.isNaN()) a.text(v) else b.text(v)
    }

    override fun children() = listOf(c, a, b)
}

/** `a == b` ([equal]) or `a != b` of two texts: 1 when true, else 0. */
internal class TextCompare(val a: TextNode, val b: TextNode, val equal: Boolean) : Node() {

    override fun eval(v: DoubleArray): Double = if ((a.text(v) == b.text(v)) == equal) 1.0 else 0.0
    override fun children() = listOf(a, b)
}

/**
 * A text part that keeps its last result. Its values are worked out every time (a number is cheap, a
 * text below keeps its own result), and only when one of them changed, or the [FormulaSetting] did,
 * the text is made again. So `text(floor(t), '0')` makes a new text once a second and not every frame.
 *
 * A number counts as the same by its bits (so NaN is the same as NaN), a text by being the very same
 * object: a part that did not change gives the same object again.
 */
internal abstract class KeptTextNode(protected val args: Array<Part>, protected val setting: FormulaSetting) : TextNode() {

    private val numbers = DoubleArray(args.size)
    private val texts = arrayOfNulls<String>(args.size)
    private var last: String? = null
    private var lastGeneration = -1

    final override fun text(v: DoubleArray): String {
        var same = last != null && (!usesSetting || lastGeneration == setting.generation)
        for (i in args.indices) {
            val arg = args[i]
            if (arg is Node) {
                val x = arg.eval(v)
                if (same && x.toRawBits() != numbers[i].toRawBits()) same = false
                numbers[i] = x
            } else {
                val s = (arg as TextNode).text(v)
                if (same && s !== texts[i]) same = false
                texts[i] = s
            }
        }
        last?.let { if (same) return it }
        lastGeneration = setting.generation
        return make(numbers, texts).also { last = it }
    }

    /** The text for the values of the parts: [n] holds the numbers and [t] the texts, each at the place of its part. */
    protected abstract fun make(n: DoubleArray, t: Array<String?>): String

    override fun children(): List<Part> = args.asList()
}
