package link.socket.phosphor.lumos.compose.material

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * PHO-63 spike, Android: the same source as AGSL through [RuntimeShader], which exists from API 33.
 * Below that there is no runtime shader on the platform at all, so the painter is told so and
 * draws flat discs — the `Flat` fallback through `onMaterialStatus` that question 1 asks about.
 */
internal actual fun createMaterialShader(kind: MaterialKind): MaterialShader? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) AndroidMaterialShader(kind) else null

@RequiresApi(Build.VERSION_CODES.TIRAMISU)
private class AndroidMaterialShader(
    kind: MaterialKind,
) : MaterialShader {
    private val glass = kind == MaterialKind.Glass
    private val shader = RuntimeShader(if (glass) MaterialSksl.GLASS else MaterialSksl.METAL)

    // One brush for the painter's lifetime: setting a uniform discards the shader's native
    // instance, so each drawCircle records the uniforms current when it was recorded.
    private val brush: Brush = ShaderBrush(shader)

    init {
        if (glass) setBackdrop(null)
    }

    override fun setBackdrop(backdrop: ImageBitmap?) {
        if (!glass) return
        val bitmap =
            backdrop?.asAndroidBitmap()
                ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also { it.eraseColor(android.graphics.Color.BLACK) }
        shader.setInputShader("backdrop", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
    }

    override fun brush(cell: MaterialCell): Brush {
        shader.setFloatUniform("center", cell.centerX, cell.centerY)
        shader.setFloatUniform("radius", cell.radius)
        shader.setFloatUniform("tint", cell.red, cell.green, cell.blue, cell.alpha)
        shader.setFloatUniform("time", cell.time)
        shader.setFloatUniform("lightAngle", cell.lightAngle)
        shader.setFloatUniform("pointer", cell.pointerX, cell.pointerY)
        if (glass) {
            shader.setFloatUniform("refraction", MaterialSksl.DEFAULT_REFRACTION)
            shader.setFloatUniform("dispersion", MaterialSksl.DEFAULT_DISPERSION)
            shader.setFloatUniform("frost", MaterialSksl.DEFAULT_FROST)
            shader.setFloatUniform("intensity", MaterialSksl.DEFAULT_INTENSITY)
        } else {
            shader.setFloatUniform("roughness", MaterialSksl.DEFAULT_ROUGHNESS)
            shader.setFloatUniform("chromatic", MaterialSksl.DEFAULT_CHROMATIC)
        }
        return brush
    }

    override fun close() = Unit
}
