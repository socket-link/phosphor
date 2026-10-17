package link.socket.phosphor.lumos.compose.docs

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosRenderConfig
import link.socket.phosphor.lumos.compose.LumosCanvas
import link.socket.phosphor.lumos.compose.lumosPointer
import link.socket.phosphor.lumos.projection.PixelLattice
import link.socket.phosphor.palette.AtmospherePresets

@Composable
fun PointedCanvas() {
    // 1. An interactive scene: its cells part around the pointer
    val source = remember { LiveFrameSource(AtmospherePresets.THINKING, LumosRenderConfig(interactive = true)) }
    val lattice = remember { PixelLattice(widthPx = 160, heightPx = 160, voxelRadiusPx = 4f) }

    // 2. Your own clock: project each frame as it is stepped
    var frame by remember { mutableStateOf(lattice.project(source.next(0f))) }
    LaunchedEffect(source) {
        var last = withFrameNanos { it }
        while (true) {
            withFrameNanos { now ->
                frame = lattice.project(source.next((now - last) / 1_000_000_000f))
                last = now
            }
        }
    }

    // 3. The pointer, in the orb's view space, from one modifier that never consumes it;
    //    the projected frame then carries it in pixels for a material's light
    LumosCanvas(frame, Modifier.size(160.dp).lumosPointer { source.setInput(it) })
}
