package link.socket.phosphor.lumos.compose

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.currentComposer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay
import link.socket.phosphor.lumos.LookClock
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.PlatformMotionPreference
import link.socket.phosphor.lumos.probe.FrameProbe

/**
 * The [LookClock] the orbs below share, so orbs of one look breathe together. One per process
 * unless a tree provides its own.
 */
val LocalLookClock: ProvidableCompositionLocal<LookClock> = staticCompositionLocalOf { LookClock() }

/**
 * A Lumos orb that owns its clock: it steps [source] once a frame and draws what comes back
 * through [LumosCanvas].
 *
 * [LumosCanvas] draws the frame it is given and nothing else. This is the driver around it, so a
 * consumer gets the ergonomics without owning a loop:
 *
 * - **Speed.** Each step is the time since the last frame, times [LumosConfig.speed]; breath,
 *   pattern, spin, transitions and glyph windows all follow. An atmosphere's own rates never change.
 * - **No jumps.** A step is capped at a tenth of a second before speed applies, so an orb back from
 *   the background or from off screen carries on from where it was.
 * - **Off screen, it stops.** The orb steps only while it is attached, its bounds intersect the
 *   window, and — where the host provides a lifecycle — the lifecycle is at least STARTED. A host
 *   without one, such as `ImageComposeScene`, is not gated. A column of orbs costs nothing for the
 *   rows scrolled away.
 * - **Paused.** [LumosConfig.paused] stops the clock and holds the last frame. A new atmosphere set
 *   while paused still draws, once, held still.
 * - **Reduced motion.** When [LumosConfig.reducedMotion] or [PlatformMotionPreference] asks, the orb
 *   draws its current pose without moving, lands atmosphere transitions on their target at once,
 *   and shows a glyph at full strength for its window instead of fading it.
 * - **A shared clock.** Orbs whose sources share a [LumosFrameSource.lookKey] and run at one speed
 *   read one [LocalLookClock] time. An orb that starts or resumes stepping takes that look's phase,
 *   so a row scrolled into view breathes with its neighbours. An orb unpaused while its peers kept
 *   moving catches up to them as it resumes.
 * - **Screen readers.** With a [LumosConfig.label], the orb is one image described by it. Without
 *   one, screen readers skip it.
 *
 * One orb per source: a source stepped by two orbs runs twice as fast. To show one orb in two
 * places, project its frame once and draw it with two [LumosCanvas]es.
 *
 * Give the orb an explicit, square size, as [LumosCanvas] expects. The projection follows the laid
 * out size, with each voxel half a lattice cell across, times [LumosConfig.dotSize].
 *
 * @param source Where frames come from; see [LumosFrameSource]. Remember it across recompositions:
 *  a new source restarts the orb.
 * @param config Speed, pause, reduced motion, label and the drawing knobs; see [LumosConfig].
 * @param modifier Sizing and placement; must give the orb a size.
 * @param backgroundColor Optional opaque background drawn before the halo and voxels.
 * @param showHalo Paint the ambient breath-ring gradient behind the orb.
 * @param showGlyphOverlay Overlay the active glyph when the frame carries one.
 * @param probe Per-phase timing sink, forwarded to the projection and the draw.
 */
@Composable
fun LumosOrb(
    source: LumosFrameSource,
    config: LumosConfig = LumosConfig(),
    modifier: Modifier = Modifier,
    backgroundColor: Color = Color.Transparent,
    showHalo: Boolean = true,
    showGlyphOverlay: Boolean = true,
    probe: FrameProbe = FrameProbe.Disabled,
) {
    val clock = LocalLookClock.current
    val driver = remember(source, clock) { LumosDriver(source, clock) }
    SideEffect { driver.update(config, probe) }

    var onScreen by remember { mutableStateOf(false) }
    val started = hostLifecycle()?.currentStateAsState()?.value?.isAtLeast(Lifecycle.State.STARTED) ?: true
    val running = onScreen && started && !config.paused
    val holding = onScreen && started && config.paused

    LaunchedEffect(driver, running) {
        if (!running) return@LaunchedEffect
        driver.rejoin()
        while (true) {
            withFrameNanos(driver.onFrame)
            // A held pose only changes at a state or glyph boundary; a slower beat spares a
            // device whose owner asked it for less motion.
            if (driver.reducedMotion) delay(REDUCED_MOTION_INTERVAL)
        }
    }

    LaunchedEffect(driver, holding) {
        if (!holding) return@LaunchedEffect
        driver.hold()
        while (true) {
            delay(PAUSED_POLL_INTERVAL)
            driver.pollHeld()
        }
    }

    Box(
        modifier =
            modifier
                .lumosSemantics(config.label)
                .onSizeChanged { driver.resize(it.width, it.height) }
                .onGloballyPositioned { coordinates ->
                    onScreen = coordinates.isAttached && !coordinates.boundsInWindow().isEmpty
                },
    ) {
        HeldCanvas(driver, backgroundColor, showHalo, showGlyphOverlay, probe)
    }
}

// Reads the driver's frame in a scope of its own, so a new frame recomposes the canvas and not
// the orb around it.
@Composable
private fun HeldCanvas(
    driver: LumosDriver,
    backgroundColor: Color,
    showHalo: Boolean,
    showGlyphOverlay: Boolean,
    probe: FrameProbe,
) {
    val frame = driver.canvasFrame ?: return
    LumosCanvas(
        frame = frame,
        modifier = Modifier.fillMaxSize(),
        backgroundColor = backgroundColor,
        showHalo = showHalo,
        showGlyphOverlay = showGlyphOverlay,
        probe = probe,
    )
}

/**
 * One image described by [label], or — without one — nothing a screen reader stops on.
 */
internal fun Modifier.lumosSemantics(label: String?): Modifier =
    if (label == null) {
        clearAndSetSemantics { }
    } else {
        semantics {
            role = Role.Image
            contentDescription = label
        }
    }

/**
 * The lifecycle of the host this composition runs in, or null when the host provides none.
 *
 * Off Android, reading `LocalLifecycleOwner.current` where no owner was provided throws, and
 * not every host provides one — a window or a view controller does, `ImageComposeScene` does not.
 * The composition-local map reads the same value without a composable call, so the read can be
 * guarded.
 */
@Composable
private fun hostLifecycle(): Lifecycle? {
    val locals = currentComposer.currentCompositionLocalMap
    return remember(locals) {
        try {
            locals[LocalLifecycleOwner].lifecycle
        } catch (_: IllegalStateException) {
            null
        }
    }
}

private val REDUCED_MOTION_INTERVAL = 64.milliseconds
private val PAUSED_POLL_INTERVAL = 250.milliseconds
