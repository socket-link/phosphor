package link.socket.phosphor.lumos.compose.docs

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.compose.LumosOrb
import link.socket.phosphor.trace.LumosSource
import link.socket.phosphor.trace.TraceCodec
import link.socket.phosphor.trace.asFrameSource

@Composable
fun ReplayedOrb(vxt: ByteArray) {
    // 1. Decode a recorded .vxt once; the bytes come from your resources, a file or the network.
    //    LumosSource.Trace(trace, initialSegment = "thinking") starts on a named segment.
    val replay = remember(vxt) { LumosSource.Trace(TraceCodec.decode(vxt).getOrThrow()) }
    val source = remember(replay) { replay.asFrameSource() }

    // 2. The same orb and the same config as a live one: only the source changed
    LumosOrb(
        source = source,
        config = LumosConfig(label = "Lumos, replayed"),
        modifier = Modifier.size(160.dp),
    )

    // 3. The player is the handle, on the UI thread: replay.player.pause(), .play(), .setSegment("ready")
}
