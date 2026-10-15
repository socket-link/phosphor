package link.socket.phosphor.lumos.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.skia.Image

/**
 * An [ImageComposeScene] whose effects run on the test thread, between frames, on virtual time.
 *
 * `ImageComposeScene`'s default context is `Dispatchers.Unconfined`, so a `delay` inside a
 * `LaunchedEffect` resumes on the coroutine timer thread and runs there, inside Compose's effect
 * dispatcher. If the test thread is in `render` at that moment it holds the frame clock's lock and
 * wants the dispatcher's; the timer thread holds the dispatcher's and, reaching `withFrameNanos`,
 * wants the clock's. [LumosOrb] runs such a loop for a paused or reduced-motion orb, and CI sat in
 * that deadlock for an hour at a time. A real host renders and resumes effects on one thread, so
 * only a scene without a main dispatcher can get here.
 *
 * Here every effect is queued on a [StandardTestDispatcher] and run by [render], on the thread that
 * calls it. Each frame advances the scheduler by the frame's length, so `delay`s elapse in step with
 * the frames the orb sees.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class SteppedScene(
    width: Int,
    height: Int,
    density: Density = Density(1f),
    private val frameNanos: Long = FRAME_NANOS,
    content: @Composable () -> Unit,
) : AutoCloseable {
    private val scheduler = TestCoroutineScheduler()
    private val scene =
        ImageComposeScene(
            width = width,
            height = height,
            density = density,
            coroutineContext = StandardTestDispatcher(scheduler),
            content = content,
        )

    /** The frame time of the last [render], in nanoseconds. */
    var nowNanos: Long = 0L
        private set

    /** Run pending effects, then draw the frame at [nowNanos]. */
    fun render(): Image {
        scheduler.runCurrent()
        return scene.render(nowNanos)
    }

    /** Step one frame forward, letting the frame's length pass for any `delay`, and draw it. */
    fun renderNextFrame(): Image {
        nowNanos += frameNanos
        scheduler.advanceTimeBy(frameNanos / NANOS_PER_MILLI)
        return render()
    }

    /** Draw the current frame, then frame by frame until [nanos] has been reached. */
    fun renderUntil(nanos: Long): Image {
        var image = render()
        while (nowNanos < nanos) image = renderNextFrame()
        return image
    }

    override fun close() = scene.close()

    private companion object {
        const val FRAME_NANOS = 33_333_333L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}
