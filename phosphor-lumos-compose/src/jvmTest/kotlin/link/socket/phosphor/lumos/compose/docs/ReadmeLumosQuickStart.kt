package link.socket.phosphor.lumos.compose.docs

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.compose.LumosOrb
import link.socket.phosphor.palette.AtmospherePresets

@Composable
fun ThinkingOrb() {
    // 1. A live scene with nothing in it but an atmosphere
    val source = remember { LiveFrameSource(AtmospherePresets.THINKING) }

    // 2. The orb owns the clock: speed, pause, reduced motion and the label are config
    LumosOrb(
        source = source,
        config = LumosConfig(label = "Lumos is thinking"),
        modifier = Modifier.size(160.dp),
    )

    // 3. Change what it shows from the UI thread, and it carries on from where it is:
    //    source.runtime.setAtmospherePreset("ready")
}
