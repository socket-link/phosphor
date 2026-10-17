# Lumos forms

Where the orb's cells rest, and how to change it.

## Forms

Where the cells rest. The default is `Forms.Lattice(resolution)`, the sphere-shaped integer lattice: its count is fixed by the atmosphere's resolution (171 at 3, 1,141 at 6, 4,801 at 10) and it follows a resolution change. `Forms.All` lists the catalogue forms, which take the count they are given; `Forms.byName(name)` finds one.

| Name | Kotlin | Scale | Tip | Meaning |
|---|---|---|---|---|
| `fibonacci` | `Forms.Fibonacci` | 1 | 0° | `count` points spread evenly over the sphere's surface on the golden-angle spiral. The inline orb's form; any count from 1 works. |
| `cube` | `Forms.Cube` | 1.2 | 0° | A cube inscribed in the sphere: the smallest `6n² + 2`-point surface grid that holds `count`, thinned evenly to exactly `count`. At 8 it is the corners. |

Scale is how much larger than the unit sphere a framed form is drawn: a solid whose corners touch the sphere is drawn larger so its faces reach it too. Tip is the view angle a form asks for; pass it as `LumosConfig.tiltDegrees`.

Set a form once, when the source is made: `source.builder.form = Forms.Cube.counted(200)`. `counted(n)` pins any form to `n` cells. To reshape a running orb, `morphTo`; see Changing the orb over time.

### A small orb

Size the cell count to the orb. `Forms.inlineOrbCount(sizePx, density)` is `sizePx × 4 × density`, never fewer than 8: 80 cells at 20 px. A resolution-10 lattice at that size is 4,801 cells, most of them below the 0.45 px floor every style draws at.

```kotlin
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
```

---

Generated from the Lumos catalogues by `./gradlew :phosphor-lumos:generateAgentDocs`. Do not edit by hand.
