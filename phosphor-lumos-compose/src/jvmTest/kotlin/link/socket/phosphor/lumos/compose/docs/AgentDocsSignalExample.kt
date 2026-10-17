package link.socket.phosphor.lumos.compose.docs

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import link.socket.phosphor.lumos.LumosConfig
import link.socket.phosphor.lumos.compose.LumosOrb
import link.socket.phosphor.palette.AtmospherePresets
import link.socket.phosphor.runtime.SceneConfiguration
import link.socket.phosphor.signal.AgentActivityState
import link.socket.phosphor.signal.CognitivePhase
import link.socket.phosphor.trace.SignalGenerator

@Composable
fun AgentOrb(
    phase: CognitivePhase,
    activity: AgentActivityState,
    escalated: Boolean,
) {
    // 1. A scene with nothing in it but an atmosphere; the first setter adds one agent to it
    //    and installs CognitiveLookSwitch's default phase → look table
    val generator =
        remember {
            SignalGenerator(
                SceneConfiguration(
                    width = 1,
                    height = 1,
                    enableWaveform = false,
                    enableParticles = false,
                    enableFlow = false,
                    enableEmitters = false,
                    enableCamera = false,
                    enableAtmosphere = true,
                    initialAtmosphere = AtmospherePresets.THINKING,
                ),
            )
        }
    val source = remember(generator) { generator.asFrameSource() }

    // 2. Hand it the agent's signal; the orb picks its look and fires the row's glyph
    LaunchedEffect(phase, activity, escalated) {
        generator.setPhase(phase)
        generator.setActivity(activity)
        generator.setEscalated(escalated)
    }

    LumosOrb(
        source = source,
        config = LumosConfig(label = "Lumos, ${phase.name.lowercase()}"),
        modifier = Modifier.size(160.dp),
    )
}
