package link.socket.phosphor.lumos.compose

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.scene.ComposeScenePointer
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
 *
 * Pointer events pass straight through to the scene ([sendPointerEvent], [sendTouch]): a pointer
 * handler resumes inside the dispatch, so what it reports is reported before the call returns.
 */
@OptIn(ExperimentalCoroutinesApi::class, ExperimentalComposeUiApi::class)
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

    /** A mouse at [position]: `Enter`, `Move`, `Exit`, or `Press` and `Release` of [button]. */
    fun sendPointerEvent(
        eventType: PointerEventType,
        position: Offset,
        buttons: PointerButtons = PointerButtons(),
        button: PointerButton? = null,
        type: PointerType = PointerType.Mouse,
    ) = scene.sendPointerEvent(
        eventType = eventType,
        position = position,
        buttons = buttons,
        button = button,
        type = type,
        timeMillis = nowNanos / NANOS_PER_MILLI,
    )

    /** One finger at [position]: `Press`, then `Move`s while [pressed], then `Release`. */
    fun sendTouch(
        eventType: PointerEventType,
        position: Offset,
        pressed: Boolean,
    ) = scene.sendPointerEvent(
        eventType = eventType,
        pointers = listOf(ComposeScenePointer(PointerId(TOUCH_ID), position, pressed, PointerType.Touch)),
        timeMillis = nowNanos / NANOS_PER_MILLI,
    )

    override fun close() = scene.close()

    private companion object {
        const val FRAME_NANOS = 33_333_333L
        const val NANOS_PER_MILLI = 1_000_000L
        const val TOUCH_ID = 1L
    }
}
