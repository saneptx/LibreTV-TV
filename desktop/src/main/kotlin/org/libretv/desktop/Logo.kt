package org.libretv.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.io.File

/** SuperTV 标志（与手机、电视同一个）：黑底上一束光，中间是白色播放三角。画布 108×108。 */
private val beams = listOf(0.10f to floatArrayOf(0f, 47f, 108f, 8f, 108f, 100f, 0f, 61f), 0.14f to floatArrayOf(0f, 50f, 108f, 24f, 108f, 84f, 0f, 58f), 0.20f to floatArrayOf(0f, 52.5f, 108f, 40f, 108f, 68f, 0f, 55.5f))
private val triangle = floatArrayOf(41f, 32f, 41f, 76f, 80f, 54f)

@Composable
fun Logo(size: Dp = 28.dp, rounded: Boolean = true) = Canvas(Modifier.size(size)) {
    val k = this.size.width / 108f
    fun path(v: FloatArray) = Path().apply { moveTo(v[0] * k, v[1] * k); for (i in 2 until v.size step 2) lineTo(v[i] * k, v[i + 1] * k); close() }
    if (rounded) drawRoundRect(Color.Black, cornerRadius = androidx.compose.ui.geometry.CornerRadius(22f * k)) else drawRect(Color.Black)
    beams.forEach { (alpha, v) -> drawPath(path(v), Color.White.copy(alpha = alpha)) }
    drawPath(path(triangle), Color.White, style = Fill); drawPath(path(triangle), Color.White, style = Stroke(9f * k, join = StrokeJoin.Round))
}

/** 生成程序图标的 PNG（打包用）：gradle generateIcons */
object IconGen {
    @JvmStatic fun main(args: Array<String>) {
        val out = File(args.firstOrNull() ?: "icons"); out.mkdirs()
        for (size in listOf(16, 24, 32, 48, 64, 128, 256, 512, 1024)) {
            val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(size, size)
            val canvas = surface.canvas; val k = size / 108f
            fun path(v: FloatArray) = org.jetbrains.skia.Path().apply { moveTo(v[0] * k, v[1] * k); for (i in 2 until v.size step 2) lineTo(v[i] * k, v[i + 1] * k); closePath() }
            val paint = org.jetbrains.skia.Paint().apply { isAntiAlias = true }
            paint.color = 0xFF000000.toInt(); canvas.drawRRect(org.jetbrains.skia.RRect.makeXYWH(0f, 0f, size.toFloat(), size.toFloat(), 22f * k), paint)
            beams.forEach { (alpha, v) -> paint.color = org.jetbrains.skia.Color.makeARGB((alpha * 255).toInt(), 255, 255, 255); canvas.drawPath(path(v), paint) }
            paint.color = 0xFFFFFFFF.toInt(); canvas.drawPath(path(triangle), paint)
            paint.mode = org.jetbrains.skia.PaintMode.STROKE; paint.strokeWidth = 9f * k; paint.strokeJoin = org.jetbrains.skia.PaintStrokeJoin.ROUND; canvas.drawPath(path(triangle), paint)
            File(out, "supertv-$size.png").writeBytes(surface.makeImageSnapshot().encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
        }
    }
}
