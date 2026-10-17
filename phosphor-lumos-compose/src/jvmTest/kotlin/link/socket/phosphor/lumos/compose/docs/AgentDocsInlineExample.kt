package link.socket.phosphor.lumos.compose.docs

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.phosphor.lumos.Forms
import link.socket.phosphor.lumos.LiveFrameSource
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.compose.LumosOrb
import link.socket.phosphor.lumos.counted
import link.socket.phosphor.palette.AtmospherePresets

@Composable
fun InlineOrb() {
    // A 20 dp orb beside a line of text: the Fibonacci sphere at 80 cells, not the 4,801-cell lattice
    val source =
        remember {
            LiveFrameSource(AtmospherePresets.THINKING).also { live ->
                live.builder.form = Forms.Fibonacci.counted(Forms.inlineOrbCount(sizePx = 20))
            }
        }

    LumosOrb(
        source = source,
        config = LumosConfig(label = "Lumos is thinking"),
        modifier = Modifier.size(20.dp),
    )
}
