package link.socket.phosphor.lumos

/**
 * Where a driver's frames come from.
 *
 * A driver owns time: when the orb steps and by how much — speed, pause, the visibility stop, the
 * cap on a step, the shared [LookClock]. A source owns what the orb is and how it answers that
 * time. The one thing every source must do is [next], so a lambda is a source:
 *
 * ```kotlin
 * val source = LumosFrameSource { dt -> builder.build(runtime.update(dt), dt) }
 * ```
 *
 * The other members have defaults, and a source that can do better overrides them: [still] holds
 * a pose for reduced motion and for a paused orb's redraw, [revision] tells a paused driver that
 * something changed, and [lookKey], [align] and [alignLook] let orbs of one look share a phase.
 * [LiveFrameSource] overrides all of them for a live scene; `:phosphor-trace` adapts its players
 * with `asFrameSource()`.
 *
 * One driver per source: two drivers stepping one source run it twice as fast. To show one orb in
 * two places, draw its frame twice.
 */
fun interface LumosFrameSource {
    /**
     * Step [dt] seconds and return the frame to draw.
     *
     * [dt] is already scaled by speed and capped by the driver; 0 means no time has passed.
     */
    fun next(dt: Float): VoxelFrame

    /**
     * The frame to draw when nothing may move: every tick under reduced motion, and once when a
     * paused orb sees [revision] change.
     *
     * [dt] still passes for whatever ends on its own, so a glyph's window closes on time and a
     * transition lands, but what is drawn is where the source is heading, held still. By default
     * this is [next] with no time passing, so a source that can do no better holds its pose and
     * its clock.
     */
    fun still(dt: Float): VoxelFrame = next(0f)

    /**
     * Changes when what [still] would draw changes without time passing — a new atmosphere, a
     * requested state. A paused driver polls it. Never changes by default.
     */
    val revision: Long get() = 0L

    /**
     * The look this source is in, or heading to; today, the atmosphere preset's name. Orbs with
     * the same key at the same speed share a [LookClock]. Null, the default, keeps a clock of its
     * own.
     */
    val lookKey: String? get() = null

    /**
     * The driver's orb has just started or resumed stepping on its [lookKey]'s clock, which reads
     * [lookTime] seconds: take the phase every orb of that look holds at that time, unless
     * something is mid-flight. Called when the orb is first drawn, scrolled back into view, back
     * from the background, or unpaused, so it rejoins its peers rather than keeping the phase it
     * left with. Does nothing by default.
     *
     * A driver calls this, not [alignLook], when the orb rejoins on a look other than the one it
     * left with; the source decides how much of a change in flight it may move.
     */
    fun align(lookTime: Double) {}

    /**
     * The source's look changed, and its new key's clock reads [lookTime]: take that look's phase
     * and nothing else. Does nothing by default.
     *
     * A driver calls this on the step its [lookKey] first reads a different look, while the orb
     * is stepping rather than rejoining, so an orb whose look switches on screen takes up the
     * incoming look in phase with the orbs already on that look's clock. Only the look counts: a
     * change of speed alone moves the orb onto another key but never aligns it. Unlike [align],
     * nothing else may move: the spin, the breath and any look being left keep their own phases,
     * so the switch shows no jump.
     */
    fun alignLook(lookTime: Double) {}
}
