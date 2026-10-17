package link.socket.phosphor.lumos.compose.material

import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import kotlin.math.roundToInt
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.compose.frame.LumosCanvasFrame
import link.socket.phosphor.lumos.counted
import link.socket.phosphor.lumos.compose.projection.ComposeLattice
import link.socket.phosphor.lumos.probe.FramePhase
import link.socket.phosphor.lumos.probe.FrameProbe
import link.socket.phosphor.lumos.probe.FrameProbeSummary
import link.socket.phosphor.palette.AtmospherePresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PHO-63 spike, Android half: metal and glass through AGSL `RuntimeShader` on a real HWUI
 * pipeline, under each [ShaderConstruction]. Writes a PNG per case to the app's external files
 * directory (pull with `adb pull /sdcard/Android/data/<test package>/files/`) and logs DRAW
 * p50/p95 under the tag `PHO-63`.
 */
@RunWith(AndroidJUnit4::class)
class MaterialShaderAndroidTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun metalAndGlassDrawUnderEveryConstructionAndReportTheirStatus() {
        val sdk = Build.VERSION.SDK_INT
        // The canvas is sized in dp; the frame is projected for the pixels that dp comes to here.
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        val largePx = (LARGE_DP * density).roundToInt()
        val smallPx = (SMALL_DP * density).roundToInt()
        val frame200 = inlineFrame(largePx, INLINE_CELLS)
        val frame20 = inlineFrame(smallPx, INLINE_CELLS)
        val lattice = inlineFrame(largePx, LATTICE_CELLS)
        val statuses = mutableMapOf<String, MaterialStatus>()
        var kind by mutableStateOf(MaterialKind.Metal)
        var construction by mutableStateOf(ShaderConstruction.InRemember)
        var frame by mutableStateOf(frame200)
        var dp by mutableStateOf(LARGE_DP)
        val probe = ListProbe()

        rule.setContent {
            key(kind, construction, dp) {
                LumosMaterialCanvas(
                    frame = frame,
                    kind = kind,
                    construction = construction,
                    modifier = Modifier.size(dp.dp).testTag(TAG_ORB),
                    backgroundColor = BACKGROUND,
                    probe = probe,
                    onMaterialStatus = { statuses["$kind/$construction"] = it },
                )
            }
        }

        val report = StringBuilder("sdk=$sdk density=$density largePx=$largePx smallPx=$smallPx\n")
        for (k in MaterialKind.entries) {
            for (c in ShaderConstruction.entries) {
                rule.runOnIdle {
                    kind = k
                    construction = c
                    dp = LARGE_DP
                    frame = frame200
                }
                rule.waitForIdle()
                val image = rule.onNodeWithTag(TAG_ORB).captureToImage()
                save(image, "material-android-$k-$c.png")
                val inked = image.inked()
                val status = statuses["$k/$c"]
                report.append("| $k | $c | inked=$inked | status=$status |\n")
                Log.i(TAG, "$k under $c: inked=$inked status=$status")
                if (sdk >= Build.VERSION_CODES.TIRAMISU) {
                    assertTrue("$k under $c drew nothing", inked)
                    assertEquals("$k under $c", MaterialStatus.Available, status)
                } else {
                    assertEquals("$k under $c below API 33", MaterialStatus.Unavailable, status)
                }
            }
        }

        // DRAW timings: the canvas is redrawn by handing it a copy of the frame with a new tick.
        val scenarios =
            listOf(
                Triple("${frame200.voxels.size} cells @ $LARGE_DP dp ($largePx px)", frame200, LARGE_DP),
                Triple("${frame20.voxels.size} cells @ $SMALL_DP dp ($smallPx px)", frame20, SMALL_DP),
                Triple("lattice ${lattice.voxels.size} cells @ $LARGE_DP dp ($largePx px)", lattice, LARGE_DP),
            )
        for ((name, base, size) in scenarios) {
            for (k in MaterialKind.entries) {
                rule.runOnIdle {
                    kind = k
                    construction = ShaderConstruction.InRemember
                    dp = size
                    frame = base
                }
                rule.waitForIdle()
                probe.reset()
                for (i in 1..BENCH_FRAMES) {
                    rule.runOnIdle { frame = base.copy(tick = base.tick + i) }
                    rule.waitForIdle()
                }
                val line = "$name, $k: ${probe.describe()}"
                Log.i(TAG, line)
                report.append(line).append('\n')
            }
        }
        File(outDir(), "material-android-report.md").writeText(report.toString())
        Log.i(TAG, report.toString())
    }

    private fun inlineFrame(
        px: Int,
        cells: Int,
    ): LumosCanvasFrame {
        val source = LiveFrameSource(AtmospherePresets.IDLE).also { it.builder.look = Look(LumosLook.BASE) }
        if (cells != LATTICE_CELLS) {
            source.builder.form = Forms.Fibonacci.counted(cells)
        }
        val frame = source.next(WARM_UP_SECONDS)
        return ComposeLattice(widthPx = px, heightPx = px, voxelRadiusPx = px / (4f * frame.resolution)).project(frame)
    }

    private fun ImageBitmap.inked(): Boolean {
        val pixels = toPixelMap()
        for (y in 0 until height step 2) {
            for (x in 0 until width step 2) {
                if (pixels[x, y] != BACKGROUND) return true
            }
        }
        return false
    }

    private fun save(
        image: ImageBitmap,
        name: String,
    ) {
        File(outDir(), name).outputStream().use { image.asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun outDir(): File =
        checkNotNull(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null)).also { it.mkdirs() }

    /** A probe that keeps every DRAW sample so the test can print its own percentiles. */
    private class ListProbe : FrameProbe {
        override val isEnabled: Boolean = true
        private val draws = mutableListOf<Long>()

        override fun record(
            phase: FramePhase,
            nanos: Long,
        ) {
            if (phase == FramePhase.DRAW) draws += nanos
        }

        override fun summary(): FrameProbeSummary = FrameProbeSummary(emptyMap())

        override fun reset() = draws.clear()

        fun describe(): String {
            val sorted = draws.sorted()
            if (sorted.isEmpty()) return "no DRAW samples"
            val p50 = sorted[sorted.size / 2] / 1e6
            val p95 = sorted[sorted.size * 95 / 100] / 1e6
            return "${sorted.size} frames, DRAW p50=${"%.2f".format(p50)} ms p95=${"%.2f".format(p95)} ms"
        }
    }

    private companion object {
        const val TAG = "PHO-63"
        const val LARGE_DP = 200
        const val SMALL_DP = 20
        const val TAG_ORB = "orb"
        const val INLINE_CELLS = 80
        const val LATTICE_CELLS = -1
        const val BENCH_FRAMES = 60
        const val WARM_UP_SECONDS = 2.3f
        val BACKGROUND = Color(0xFF111111)
    }
}
