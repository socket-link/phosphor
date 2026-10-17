package link.socket.phosphor.lumos.compose.docs

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.Look
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.LumosLook
import link.socket.phosphor.lumos.Material
import link.socket.phosphor.lumos.SurfaceStyle
import link.socket.phosphor.lumos.compose.LumosOrb
import link.socket.phosphor.palette.AtmospherePresets

@Composable
fun WorkingOrb(working: Boolean) {
    val source = remember { LiveFrameSource(AtmospherePresets.THINKING) }

    // A look is how the cells move; lookTo blends to it. LaunchedEffect runs on the UI thread,
    // the thread LumosOrb steps the source on, so the builder is never touched from two threads.
    LaunchedEffect(working) {
        source.builder.lookTo(if (working) Look(LumosLook.WORKING) else Look(LumosLook.BASE))
    }

    LumosOrb(
        source = source,
        config =
            LumosConfig(
                style = SurfaceStyle.Dots,
                material = Material.Neon(glow = 0.5f),
                label = if (working) "Lumos is working" else "Lumos is thinking",
            ),
        modifier = Modifier.size(160.dp),
    )
}
