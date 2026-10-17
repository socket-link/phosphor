package link.socket.phosphor.lumos.surface

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import org.jetbrains.skia.Image

/**
 * An [ImageComposeScene] whose effects run on the test thread, between frames, on virtual time.
 * A copy of `:phosphor-lumos-compose`'s jvmTest helper of the same name, which explains the
 * deadlock the default unconfined context allows.
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

    override fun close() = scene.close()

    companion object {
        /** 30 fps. */
        const val FRAME_NANOS = 33_333_333L
        private const val NANOS_PER_MILLI = 1_000_000L
    }
}
