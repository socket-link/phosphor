package link.socket.phosphor.lumos.compose.material

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asSkiaBitmap
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder
import org.jetbrains.skia.SamplingMode
import org.jetbrains.skia.Shader

/**
 * PHO-63 spike, every Skia-backed target: the SkSL compiled once per painter through
 * [RuntimeEffect.makeForShader], uniforms set per cell on one [RuntimeShaderBuilder], and a fresh
 * [Shader] made per cell — Skia keeps the compiled program; the per-cell object is the uniform block.
 */
internal actual fun createMaterialShader(kind: MaterialKind): MaterialShader? = SkikoMaterialShader(kind)

private class SkikoMaterialShader(
    kind: MaterialKind,
) : MaterialShader {
    private val glass = kind == MaterialKind.Glass
    private val effect: RuntimeEffect = RuntimeEffect.makeForShader(if (glass) MaterialSksl.GLASS else MaterialSksl.METAL)
    private val builder = RuntimeShaderBuilder(effect)
    private var backdropImage: Image? = null
    private var backdropShader: Shader? = null

    init {
        if (glass) setBackdrop(null)
    }

    override fun setBackdrop(backdrop: ImageBitmap?) {
        if (!glass) return
        backdropShader?.close()
        backdropImage?.close()
        val image = backdrop?.let { Image.makeFromBitmap(it.asSkiaBitmap()) }
        val shader =
            image?.makeShader(FilterTileMode.CLAMP, FilterTileMode.CLAMP, SamplingMode.LINEAR, null)
                ?: Shader.makeColor(BLACK)
        backdropImage = image
        backdropShader = shader
        builder.child("backdrop", shader)
    }

    override fun brush(cell: MaterialCell): Brush {
        builder.uniform("center", cell.centerX, cell.centerY)
        builder.uniform("radius", cell.radius)
        builder.uniform("tint", cell.red, cell.green, cell.blue, cell.alpha)
        builder.uniform("time", cell.time)
        builder.uniform("lightAngle", cell.lightAngle)
        builder.uniform("pointer", cell.pointerX, cell.pointerY)
        if (glass) {
            builder.uniform("refraction", MaterialSksl.DEFAULT_REFRACTION)
            builder.uniform("dispersion", MaterialSksl.DEFAULT_DISPERSION)
            builder.uniform("frost", MaterialSksl.DEFAULT_FROST)
            builder.uniform("intensity", MaterialSksl.DEFAULT_INTENSITY)
        } else {
            builder.uniform("roughness", MaterialSksl.DEFAULT_ROUGHNESS)
            builder.uniform("chromatic", MaterialSksl.DEFAULT_CHROMATIC)
        }
        return ShaderBrush(builder.makeShader(null))
    }

    override fun close() {
        backdropShader?.close()
        backdropImage?.close()
        builder.close()
        effect.close()
    }

    private companion object {
        const val BLACK: Int = 0xFF000000.toInt()
    }
}
