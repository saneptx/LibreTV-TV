package org.libretv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 与手机端、电视端同一套黑白灰配色：纯黑底、中性灰的层次，强调只靠明暗，不用彩色。 */
object C {
    val bg = Color(0xFF000000)
    val bar = Color(0xFF0B0B0D)
    val surface = Color(0xFF1C1C1E)
    val raised = Color(0xFF2C2C2E)
    val hover = Color(0xFF3A3A3C)
    val ink = Color(0xFFF5F5F7)
    val muted = Color(0xFF98989F)
    val faint = Color(0xFF636366)
    val line = Color(0x1FFFFFFF)
}

@Composable
fun T(text: String, size: TextUnit = 14.sp, color: Color = C.ink, weight: FontWeight = FontWeight.Normal, lines: Int = Int.MAX_VALUE, modifier: Modifier = Modifier, lineHeight: TextUnit = TextUnit.Unspecified) =
    BasicText(text, modifier, style = TextStyle(color = color, fontSize = size, fontWeight = weight, lineHeight = lineHeight), maxLines = lines, overflow = TextOverflow.Ellipsis)

/** 可点击、鼠标悬停变亮的区域 */
@Composable
fun Modifier.press(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default).clickable(source, null, enabled, onClick = onClick)
}

/** 胶囊按钮；primary 为白底黑字 */
@Composable
fun Pill(text: String, primary: Boolean = false, enabled: Boolean = true, modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 10.dp), onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }; val hovered by source.collectIsHoveredAsState()
    val background = when { primary && hovered -> Color(0xFFE5E5EA); primary -> Color.White; hovered -> C.hover; else -> C.raised }
    Box(modifier.clip(RoundedCornerShape(50)).background(background).hoverable(source).pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
        .clickable(source, null, enabled, onClick = onClick).padding(padding), contentAlignment = Alignment.Center) {
        T(text, 14.sp, if (primary) Color.Black else if (enabled) C.ink else C.faint, FontWeight.SemiBold, 1)
    }
}

/** 小号可选项（片源、剧集、倍速） */
@Composable
fun Chip(text: String, selected: Boolean = false, modifier: Modifier = Modifier, minWidth: Dp = 0.dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }; val hovered by source.collectIsHoveredAsState()
    Box(modifier.defaultMinSize(minWidth = minWidth).clip(RoundedCornerShape(10.dp)).background(if (selected) Color.White else if (hovered) C.hover else C.raised)
        .hoverable(source).pointerHoverIcon(PointerIcon.Hand).clickable(source, null, onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        T(text, 13.sp, if (selected) Color.Black else C.ink, FontWeight.Medium, 1)
    }
}

/** 圆形图标按钮 */
@Composable
fun IconButton(icon: Icon, size: Dp = 40.dp, tint: Color = C.ink, filled: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }; val hovered by source.collectIsHoveredAsState()
    Box(modifier.size(size).clip(CircleShape).background(if (hovered) C.hover else if (filled) C.raised else Color.Transparent).hoverable(source)
        .pointerHoverIcon(PointerIcon.Hand).clickable(source, null, onClick = onClick), contentAlignment = Alignment.Center) {
        IconView(icon, tint, size * 0.5f)
    }
}

enum class Icon { BACK, SEARCH, PLAY, PAUSE, FULL, SHRINK, HEART, HEART_ON, CLOSE, REWIND, FORWARD, NEXT, PREV, SETTINGS, CHECK }

/** 线条图标：统一 24 单位的画布，和手机端的图标一致 */
@Composable
fun IconView(icon: Icon, tint: Color = C.ink, size: Dp = 20.dp) = Canvas(Modifier.size(size)) { drawIcon(icon, tint) }

fun DrawScope.drawIcon(icon: Icon, tint: Color) {
    val u = size.minDimension / 24f; val cx = size.width / 2; val cy = size.height / 2
    val stroke = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(cx + x * u, cy + y * u)
    fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(tint, p(x1, y1), p(x2, y2), 2f * u, StrokeCap.Round)
    fun poly(vararg v: Float, close: Boolean = false) = Path().apply { moveTo(cx + v[0] * u, cy + v[1] * u); for (i in 2 until v.size step 2) lineTo(cx + v[i] * u, cy + v[i + 1] * u); if (close) close() }
    when (icon) {
        Icon.BACK -> drawPath(poly(3f, -8f, -5f, 0f, 3f, 8f), tint, style = stroke)
        Icon.SEARCH -> { drawCircle(tint, 6.5f * u, p(-1.5f, -1.5f), style = stroke); line(3.5f, 3.5f, 8.5f, 8.5f) }
        Icon.PLAY -> drawPath(poly(-5f, -8f, 9f, 0f, -5f, 8f, close = true), tint, style = Fill)
        Icon.PAUSE -> { drawRect(tint, p(-7f, -8f), Size(5f * u, 16f * u)); drawRect(tint, p(2f, -8f), Size(5f * u, 16f * u)) }
        Icon.FULL -> { drawPath(poly(-8f, -3f, -8f, -8f, -3f, -8f), tint, style = stroke); drawPath(poly(3f, -8f, 8f, -8f, 8f, -3f), tint, style = stroke); drawPath(poly(8f, 3f, 8f, 8f, 3f, 8f), tint, style = stroke); drawPath(poly(-3f, 8f, -8f, 8f, -8f, 3f), tint, style = stroke) }
        Icon.SHRINK -> { drawPath(poly(-9f, -3f, -4f, -3f, -4f, -8f), tint, style = stroke); drawPath(poly(4f, -8f, 4f, -3f, 9f, -3f), tint, style = stroke); drawPath(poly(9f, 3f, 4f, 3f, 4f, 8f), tint, style = stroke); drawPath(poly(-4f, 8f, -4f, 3f, -9f, 3f), tint, style = stroke) }
        Icon.HEART, Icon.HEART_ON -> {
            val path = Path().apply { moveTo(cx, cy + 8 * u); cubicTo(cx - 14 * u, cy - u, cx - 7 * u, cy - 11 * u, cx, cy - 4 * u); cubicTo(cx + 7 * u, cy - 11 * u, cx + 14 * u, cy - u, cx, cy + 8 * u); close() }
            if (icon == Icon.HEART_ON) drawPath(path, tint, style = Fill)
            drawPath(path, tint, style = stroke)
        }
        Icon.CLOSE -> { line(-6f, -6f, 6f, 6f); line(6f, -6f, -6f, 6f) }
        Icon.REWIND, Icon.FORWARD -> {
            // 开口朝上的圆弧，箭头接在顶端（后退朝左、快进朝右）
            val s = if (icon == Icon.REWIND) -1f else 1f; val r = 8f * u; val o = p(0f, 1.5f)
            drawArc(tint, -90f + s * 42f, s * 276f, false, Offset(o.x - r, o.y - r), Size(2 * r, 2 * r), style = stroke)
            drawPath(poly(s * 5.2f, -6.5f, s * 0.2f, -10.3f, s * 0.2f, -2.7f, close = true), tint, style = Fill)
            // 圆心写秒数，和手机端一致
            val font = org.jetbrains.skia.Font(org.jetbrains.skia.FontMgr.default.matchFamilyStyle(null, org.jetbrains.skia.FontStyle.BOLD), 7.6f * u)
            val line = org.jetbrains.skia.TextLine.make("10", font)
            drawContext.canvas.nativeCanvas.drawTextLine(line, o.x - line.width / 2, o.y + line.capHeight / 2, org.jetbrains.skia.Paint().apply { color = tint.toArgb(); isAntiAlias = true })
        }
        Icon.NEXT -> { drawPath(poly(-7f, -7f, 4f, 0f, -7f, 7f, close = true), tint, style = Fill); line(7f, -7f, 7f, 7f) }
        Icon.PREV -> { drawPath(poly(7f, -7f, -4f, 0f, 7f, 7f, close = true), tint, style = Fill); line(-7f, -7f, -7f, 7f) }
        Icon.SETTINGS -> for ((row, knob) in listOf(-6f to 3f, 0f to -4f, 6f to 1f)) { line(-9f, row, 9f, row); drawCircle(tint, 2.8f * u, p(knob, row)) }
        Icon.CHECK -> drawPath(poly(-7f, 0f, -2f, 5f, 7f, -6f), tint, style = stroke)
    }
}

/** 白色圆点 + 字的小标签（海报左下角的集数说明） */
@Composable
fun Badge(text: String, modifier: Modifier = Modifier) = Box(modifier.clip(RoundedCornerShape(6.dp)).background(Color(0xAA000000)).padding(horizontal = 6.dp, vertical = 3.dp)) { T(text, 11.sp, Color.White, FontWeight.SemiBold, 1) }

@Composable
fun Panel(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Box(modifier.clip(RoundedCornerShape(16.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(16.dp))) { content() }

@Composable
fun Row2(modifier: Modifier = Modifier, content: @Composable () -> Unit) = Row(modifier, verticalAlignment = Alignment.CenterVertically) { content() }
