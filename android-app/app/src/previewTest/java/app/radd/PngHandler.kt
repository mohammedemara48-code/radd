package app.radd

import app.cash.paparazzi.Snapshot
import app.cash.paparazzi.SnapshotHandler
import java.io.File
import java.lang.reflect.Proxy

/**
 * Writes full-resolution PNGs straight into <project>/previews (Paparazzi's report downsizes them).
 * java.awt/javax.imageio are not on the Android compile classpath, so a dynamic proxy and reflection are used.
 */
class PngHandler(private val dir: File = File("../../previews")) : SnapshotHandler {
    override fun newFrameHandler(snapshot: Snapshot, frameCount: Int, fps: Int): SnapshotHandler.FrameHandler =
        Proxy.newProxyInstance(
            javaClass.classLoader, arrayOf(SnapshotHandler.FrameHandler::class.java),
        ) { _, method, args ->
            if (method.name == "handle") {
                dir.mkdirs()
                val imageIo = Class.forName("javax.imageio.ImageIO")
                val write = imageIo.getMethod("write", Class.forName("java.awt.image.RenderedImage"), String::class.java, File::class.java)
                write.invoke(null, args[0], "png", File(dir, "${snapshot.name}.png"))
            }
            null
        } as SnapshotHandler.FrameHandler
    override fun close() = Unit
}
