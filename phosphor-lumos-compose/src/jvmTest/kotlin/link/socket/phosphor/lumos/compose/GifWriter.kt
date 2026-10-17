package link.socket.phosphor.lumos.compose

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageTypeSpecifier
import javax.imageio.ImageWriter
import javax.imageio.metadata.IIOMetadata
import javax.imageio.metadata.IIOMetadataNode
import javax.imageio.stream.ImageOutputStream
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image

/**
 * A looping animated GIF written frame by frame through `javax.imageio`, for the recordings the
 * previews leave in `build/lumos-previews/`. Test-only: nothing in the library writes images.
 *
 * @param file Where the GIF goes; its directory is created.
 * @param frameMillis How long each frame shows, rounded to the GIF's hundredths of a second.
 */
internal class GifWriter(
    file: File,
    frameMillis: Int,
) : AutoCloseable {
    private val writer: ImageWriter = ImageIO.getImageWritersByFormatName("gif").next()
    private val output: ImageOutputStream
    private val metadata: IIOMetadata
    private var frames = 0

    init {
        file.parentFile?.mkdirs()
        output = ImageIO.createImageOutputStream(file)
        writer.output = output
        val type = ImageTypeSpecifier.createFromBufferedImageType(BufferedImage.TYPE_INT_RGB)
        metadata = writer.getDefaultImageMetadata(type, writer.defaultWriteParam)
        val root = metadata.getAsTree(metadata.nativeMetadataFormatName) as IIOMetadataNode
        root.child("GraphicControlExtension").apply {
            setAttribute("disposalMethod", "none")
            setAttribute("userInputFlag", "FALSE")
            setAttribute("transparentColorFlag", "FALSE")
            setAttribute("delayTime", (frameMillis / 10).coerceAtLeast(1).toString())
            setAttribute("transparentColorIndex", "0")
        }
        root.child("ApplicationExtensions").appendChild(
            IIOMetadataNode("ApplicationExtension").apply {
                setAttribute("applicationID", "NETSCAPE")
                setAttribute("authenticationCode", "2.0")
                // Loop forever.
                userObject = byteArrayOf(1, 0, 0)
            },
        )
        metadata.setFromTree(metadata.nativeMetadataFormatName, root)
        writer.prepareWriteSequence(null)
    }

    /** Append [image] as the next frame. */
    fun write(image: Image) {
        val png = checkNotNull(image.encodeToData(EncodedImageFormat.PNG)).bytes
        val decoded = ImageIO.read(ByteArrayInputStream(png))
        val rgb = BufferedImage(decoded.width, decoded.height, BufferedImage.TYPE_INT_RGB)
        rgb.createGraphics().apply {
            drawImage(decoded, 0, 0, null)
            dispose()
        }
        writer.writeToSequence(IIOImage(rgb, null, metadata), writer.defaultWriteParam)
        frames++
    }

    /** How many frames have been written. */
    val frameCount: Int get() = frames

    override fun close() {
        writer.endWriteSequence()
        output.close()
        writer.dispose()
    }

    private fun IIOMetadataNode.child(name: String): IIOMetadataNode {
        for (i in 0 until length) {
            val node = item(i)
            if (node.nodeName.equals(name, ignoreCase = true)) return node as IIOMetadataNode
        }
        return IIOMetadataNode(name).also { appendChild(it) }
    }
}
