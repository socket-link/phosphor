package link.socket.phosphor.lumos.dom

/** An SVG document in memory: every element it makes records its attributes and counts its writes. */
internal class FakeDocument : SvgFactory {
    val created = ArrayList<FakeNode>()

    /** Every attribute write to any element of this document, the root included. */
    var writes: Int = 0

    fun root(): FakeNode = FakeNode("svg", this)

    override fun create(tag: String): SvgNode = FakeNode(tag, this).also { created += it }
}

internal class FakeNode(
    val tag: String,
    val document: FakeDocument,
) : SvgNode {
    val attributes = LinkedHashMap<String, String>()
    val children = ArrayList<FakeNode>()

    /** Writes to this element alone. */
    var writes: Int = 0

    override fun setAttribute(
        name: String,
        value: String,
    ) {
        attributes[name] = value
        writes++
        document.writes++
    }

    override fun removeAttribute(name: String) {
        attributes.remove(name)
    }

    override fun appendChild(child: SvgNode) {
        children += child as FakeNode
    }

    override fun removeChild(child: SvgNode) {
        children.remove(child)
    }

    /** The children of [tag], in document order, depth first. */
    fun all(tag: String): List<FakeNode> =
        children.flatMap { child -> listOfNotNull(child.takeIf { it.tag == tag }) + child.all(tag) }

    /**
     * This element as markup, attributes sorted by name so the order they were written in does not
     * matter: `<circle cx="10" cy="12" r="2"/>`.
     */
    fun markup(): String {
        val attrs = attributes.entries.sortedBy { it.key }.joinToString("") { (k, v) -> " $k=\"$v\"" }
        return if (children.isEmpty()) {
            "<$tag$attrs/>"
        } else {
            "<$tag$attrs>${children.joinToString("") { it.markup() }}</$tag>"
        }
    }
}

/**
 * The browser as the driver sees it, run by hand: frames run when [frame] says, timers when
 * [advance] passes them, and the page and the viewport are whatever the test sets.
 */
internal class FakeHost : DomHost {
    var time: Double = 0.0
    var hidden: Boolean = false
    var reduceMotion: Boolean = false

    private var nextId = 1
    private val frames = LinkedHashMap<Int, (Double) -> Unit>()
    private val timers = LinkedHashMap<Int, Pair<Double, () -> Unit>>()
    private val visibilityListeners = ArrayList<() -> Unit>()
    private val intersectionListeners = ArrayList<(Boolean) -> Unit>()
    private val sizeListeners = ArrayList<(Double, Double) -> Unit>()

    var widthPx: Double = 0.0
        private set
    var heightPx: Double = 0.0
        private set

    val pendingFrames: Int get() = frames.size
    val pendingTimers: Int get() = timers.size
    val observers: Int get() = visibilityListeners.size + intersectionListeners.size + sizeListeners.size

    /** Move the clock [ms] on and run the frames requested so far at the new time. */
    fun frame(ms: Double = 1000.0 / 60.0) {
        time += ms
        val due = frames.values.toList()
        frames.clear()
        for (callback in due) callback(time)
    }

    /** Move the clock [ms] on, running each timer that falls due. */
    fun advance(ms: Double) {
        val end = time + ms
        while (true) {
            val id = timers.entries.filter { it.value.first <= end }.minByOrNull { it.value.first }?.key ?: break
            val (due, callback) = timers.remove(id)!!
            time = maxOf(time, due)
            callback()
        }
        time = end
    }

    fun setVisible(visible: Boolean) {
        hidden = !visible
        visibilityListeners.toList().forEach { it() }
    }

    fun setIntersecting(intersecting: Boolean) = intersectionListeners.toList().forEach { it(intersecting) }

    fun resize(
        width: Double,
        height: Double,
    ) {
        widthPx = width
        heightPx = height
        sizeListeners.toList().forEach { it(width, height) }
    }

    override fun requestFrame(callback: (Double) -> Unit): Int = nextId++.also { frames[it] = callback }

    override fun cancelFrame(id: Int) {
        frames.remove(id)
    }

    override fun setTimer(
        delayMs: Int,
        callback: () -> Unit,
    ): Int = nextId++.also { timers[it] = (time + delayMs) to callback }

    override fun clearTimer(id: Int) {
        timers.remove(id)
    }

    override fun now(): Double = time

    override fun documentHidden(): Boolean = hidden

    override fun onVisibilityChange(callback: () -> Unit): () -> Unit {
        visibilityListeners += callback
        return { visibilityListeners -= callback }
    }

    override fun observeIntersection(
        target: SvgNode,
        callback: (Boolean) -> Unit,
    ): () -> Unit {
        intersectionListeners += callback
        return { intersectionListeners -= callback }
    }

    override fun observeSize(
        target: SvgNode,
        callback: (widthPx: Double, heightPx: Double) -> Unit,
    ): () -> Unit {
        sizeListeners += callback
        callback(widthPx, heightPx)
        return { sizeListeners -= callback }
    }

    override fun reducesMotion(): Boolean = reduceMotion

    /** Size the next element observed before it is mounted. */
    fun sized(
        width: Double,
        height: Double,
    ): FakeHost {
        widthPx = width
        heightPx = height
        return this
    }
}
