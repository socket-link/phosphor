package link.socket.phosphor.lumos.compose.spike

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.exp
import link.socket.phosphor.choreography.AtmosphereTransitionSpec
import link.socket.phosphor.field.Choreography
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.compose.drawVoxels
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.compose.painter
import link.socket.phosphor.palette.AtmospherePresets

/**
 * PHO-68 throwaway: the orb dissolves into the Arc-creation "Turn on" button.
 *
 * The shape of the thing, Dots' `SwarmSurface` + `SwarmUI.Part` in Compose terms:
 *
 * - **Target.** The button publishes its `boundsInRoot` from `onGloballyPositioned` into
 *   [TurnOnAssembly.target]; the form reads it when it samples.
 * - **Overlay.** One root-sized `Canvas`, the **last sibling** of the root `Box`, so it draws
 *   above the button and below anything in a `Popup` or `Dialog` (separate windows). It is
 *   `clearAndSetSemantics { }` and takes no pointer input: the real button keeps click and focus.
 * - **Hand-off.** The button reads [TurnOnAssembly.buttonAlpha] in `graphicsLayer`, Dots'
 *   inline-opacity write; the cells grow and fade on the same arrival curve.
 * - **Settled stop.** When the morph settles the overlay leaves the composition and the frame
 *   loop, keyed on [TurnOnAssembly.running], ends.
 */
@Composable
fun TurnOnSpike(
    assembly: TurnOnAssembly,
    onTurnOn: () -> Unit = {},
) {
    LaunchedEffect(assembly, assembly.running) {
        if (!assembly.running) return@LaunchedEffect
        while (true) withFrameNanos(assembly::onFrame)
    }
    Box(modifier = Modifier.fillMaxSize().background(BACKGROUND)) {
        TurnOnButton(
            alpha = assembly.buttonAlpha,
            onClick = onTurnOn,
            modifier =
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(24.dp)
                    .onGloballyPositioned {
                        val r = it.boundsInRoot()
                        assembly.target = RectPx(r.left, r.top, r.width, r.height, CORNER_PX)
                    },
        )
        if (!assembly.done) {
            val frame = assembly.canvasFrame
            Canvas(modifier = Modifier.fillMaxSize().clearAndSetSemantics { }) {
                if (frame != null) drawVoxels(frame, painter)
            }
        }
    }
}

@Composable
private fun TurnOnButton(
    alpha: Float,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier =
            modifier
                .graphicsLayer { this.alpha = alpha }
                .size(width = 128.dp, height = 40.dp)
                .background(ACCENT, RoundedCornerShape(CORNER_PX.dp))
                .clickable(onClick = onClick)
                .semantics { role = Role.Button },
        contentAlignment = Alignment.Center,
    ) {
        BasicText("Turn on", style = TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium))
    }
}

private val painter = SurfaceStyle.Dots.painter()
private val BACKGROUND = Color(0xFF111318)
private val ACCENT = Color(0xFF4F6BFF)
private const val CORNER_PX = 8f

/**
 * The spike's driver: a live IDLE orb at resolution 6 (1,141 cells) in an [OrbViewport], morphed
 * into a [RectTargetForm] by `SCATTER` at [assembleAt] seconds and handed off on the spring's own
 * progress curve. Main-thread, like the builder it steps.
 */
class TurnOnAssembly(
    val widthPx: Int,
    val heightPx: Int,
    val viewport: OrbViewport,
    private val assembleAt: Float = 0.8f,
    private val morphSeconds: Float = 2.4f,
    val reducedMotion: Boolean = false,
) {
    val source = LiveFrameSource(AtmospherePresets.IDLE.copy(resolution = RESOLUTION))
    private val builder get() = source.builder
    private val form = RectTargetForm(viewport, rotation = { builder.orbRotationX to builder.orbRotationY })
    private val projection =
        ViewportProjection(widthPx, heightPx, viewport, voxelRadiusPx = viewport.radiusPx / (2f * RESOLUTION))

    var canvasFrame: LumosCanvasFrame? by mutableStateOf(null)
        private set
    var buttonAlpha: Float by mutableFloatStateOf(0f)
        private set
    var running: Boolean by mutableStateOf(true)
        private set
    var done: Boolean by mutableStateOf(false)
        private set

    /** The button's bounds in root pixels, from `onGloballyPositioned`. */
    var target: RectPx? = null
        set(value) {
            field = value
            form.rectPx = value
        }

    /** Where the morph spring stands, `0..1`; `0` before it starts. */
    var progress: Float = 0f
        private set

    /** Dots' arrival, `0..1`, from the measured mean pixel error; the hand-off curve. */
    var arrival: Float = 0f
        private set

    private var clock = 0f
    private var lastNanos = Long.MIN_VALUE
    private var started = false
    private var morphClock = 0f

    fun onFrame(nanos: Long) {
        val dt = if (lastNanos == Long.MIN_VALUE) 0f else ((nanos - lastNanos) / 1e9f).coerceIn(0f, 0.1f)
        lastNanos = nanos
        step(dt)
    }

    fun step(dt: Float) {
        if (done) return
        clock += dt
        if (!started && clock >= assembleAt && target != null) {
            started = true
            if (reducedMotion) {
                // Reduced motion: the control appears at once, no cells fly.
                builder.morphTo(form, choreography = Choreography.DIRECT, durationSeconds = 0f)
                finish()
                return
            }
            builder.morphTo(form, choreography = Choreography.FLOW, durationSeconds = morphSeconds)
            // A layout target wants the skin rigid: the atmosphere's jitter, bump, pulse and squash
            // keep riding the body and blur the rectangle's edge, so the surface crossfades the
            // atmosphere to a flat copy of itself alongside the morph. Found on the first recording.
            source.runtime.atmosphereChoreographer?.setAtmosphere(
                RIGID,
                null,
                AtmosphereTransitionSpec(durationSeconds = RIGID_SECONDS, easingName = EASING),
            )
        }
        val frame = source.next(dt)
        if (started) {
            morphClock += dt
            // FormMorph's critically damped step response, ω = 7.5 · 2.4 / duration: the builder
            // does not expose the morph's progress, so the spike recomputes it.
            val omega = 7.5f * 2.4f / morphSeconds
            val wt = omega * morphClock
            progress = if (builder.isMorphing) 1f - exp(-wt) * (1f + wt) else 1f
        }
        val projected = projection.project(frame)
        // Dots' arrival: `(8 − meanErr) / 6`, meanErr in pixels. Measured, not derived from the
        // spring: FormMorph.progress is the spring's step response and knows nothing of the
        // choreography's arc, so a hand-off keyed on progress fires while cells are still out on
        // the arc (second recording). Here the error is each cell's distance outside the target's
        // rounded rectangle, which needs no cell-to-target pairing.
        val arrival =
            if (started) {
                smoothstep((ARRIVAL_FAR_PX - meanErrorPx(projected)) / (ARRIVAL_FAR_PX - ARRIVAL_NEAR_PX))
            } else {
                0f
            }
        this.arrival = arrival
        buttonAlpha = arrival
        canvasFrame =
            if (arrival == 0f) {
                projected
            } else {
                // Dots: on arrival the cells grow and fade while the element's opacity rises.
                val grow = 1f + GROW * arrival
                val fade = 1f - arrival
                projected.copy(
                    voxels = projected.voxels.map { it.copy(radiusPx = it.radiusPx * grow, alpha = it.alpha * fade) },
                )
            }
        if (started && (arrival >= 1f || !builder.isMorphing)) finish()
    }

    private fun meanErrorPx(projected: LumosCanvasFrame): Float {
        val rect = target ?: return Float.POSITIVE_INFINITY
        if (projected.voxels.isEmpty()) return Float.POSITIVE_INFINITY
        var sum = 0f
        for (v in projected.voxels) {
            sum += roundedRectDistance(rect, rect.cornerRadius, v.screenX, v.screenY).coerceAtLeast(0f)
        }
        return sum / projected.voxels.size
    }

    /** Scroll, resize or hide mid-assembly: snap the control on and stop. */
    fun cancel() {
        if (started) builder.morphTo(form, choreography = Choreography.DIRECT, durationSeconds = 0f)
        finish()
    }

    private fun finish() {
        progress = 1f
        arrival = 1f
        buttonAlpha = 1f
        done = true
        running = false
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        const val RESOLUTION: Int = 6

        /** IDLE with the skin held still, so the assembled cells sit where the form put them. */
        val RIGID =
            AtmospherePresets.IDLE.copy(
                resolution = RESOLUTION,
                noise = 0f,
                surfaceBump = 0f,
                pulseAmplitude = 0f,
                ySquash = 1f,
                rotationY = 0f,
            )
        private const val RIGID_SECONDS: Float = 0.6f
        private const val EASING: String = "easeinoutcubic"

        /** Dots' arrival window: `arrival = (8 − meanErr) / 6`, so 0 at 8 px of mean error and 1 at 2 px. */
        const val ARRIVAL_FAR_PX: Float = 8f
        const val ARRIVAL_NEAR_PX: Float = 2f
        private const val GROW: Float = 0.6f
    }
}
