package link.socket.phosphor.lumos.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import link.socket.phosphor.lumos.LookClock
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosFrameSource
import link.socket.phosphor.lumos.OrbStepper
import link.socket.phosphor.lumos.PlatformMotionPreference
import link.socket.phosphor.lumos.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.signal.LumosInput

/**
 * [OrbStepper] behind [LumosOrb], with the frame it draws held in Compose state so the canvas
 * follows it. The rules about time are the stepper's — the step, the cap, aligning on a rejoin
 * ([LumosFrameSource.align]) and on a look change ([LumosFrameSource.alignLook]) — and this only
 * hands each new frame to Compose. The pointer is the stepper's too ([OrbStepper.setInput]): it
 * goes to the source, and the latest one is laid over every frame published, live or recorded.
 *
 * Main-thread only, like the source it steps.
 */
internal class LumosDriver(
    source: LumosFrameSource,
    clock: LookClock,
    platformReducesMotion: () -> Boolean = { PlatformMotionPreference.reduceMotion },
) {
    private val stepper = OrbStepper(source, clock, platformReducesMotion)

    /** What the canvas draws; null until a frame has been projected. Compose state, so the canvas follows it. */
    var canvasFrame: LumosCanvasFrame? by mutableStateOf(null)
        private set

    /** Whether the orb holds still: its config asks, or the platform does. */
    val reducedMotion: Boolean get() = stepper.reducedMotion

    /** The frame callback a frame loop hands to `withFrameNanos`, allocated once. */
    val onFrame: (Long) -> Unit = { frameTimeNanos -> step(frameTimeNanos) }

    /** The input callback [lumosPointer] is given, allocated once. */
    val onInput: (LumosInput) -> Unit = { input -> setInput(input) }

    /** See [OrbStepper.update]. */
    fun update(
        config: LumosConfig,
        probe: FrameProbe,
    ) = publish(stepper.update(config, probe))

    /** See [OrbStepper.resize]. */
    fun resize(
        widthPx: Int,
        heightPx: Int,
    ) = publish(stepper.resize(widthPx, heightPx))

    /** See [OrbStepper.rejoin]. */
    fun rejoin() = stepper.rejoin()

    /** See [OrbStepper.step]. */
    fun step(frameTimeNanos: Long) = publish(stepper.step(frameTimeNanos))

    /** See [OrbStepper.hold]. */
    fun hold() = publish(stepper.hold())

    /** See [OrbStepper.pollHeld]. */
    fun pollHeld() = publish(stepper.pollHeld())

    /**
     * See [OrbStepper.setInput]. Publishes nothing: a pointer move while the orb is paused or
     * under reduced motion is lit on the next frame drawn for another reason.
     */
    fun setInput(input: LumosInput) = stepper.setInput(input)

    // Only a new projection is written, so an unchanged frame costs Compose nothing.
    private fun publish(frame: LumosCanvasFrame?) {
        if (frame != null) canvasFrame = frame
    }
}
