package org.libretv.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sun.jna.Native
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.ptr.IntByReference
import java.awt.Cursor
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window

/**
 * Windows 上的窗口外观：不用系统标题栏，标题栏融进顶栏，最小化 / 最大化 / 关闭按钮和边框都按 SuperTV 的样式自己画。
 * 最大化、全屏也自己实现（直接设窗口范围），避开系统全屏切换后界面尺寸错乱的问题。
 */
class Chrome(val window: Window, val custom: Boolean) {
    var maximized by mutableStateOf(false); private set
    var fullscreen by mutableStateOf(false); private set
    private var normal: Rectangle? = null
    init {
        // 最大化、全屏铺满整个屏幕并盖住任务栏：窗口在前台时置顶，切到别的程序时取消，不挡别人
        if (custom) window.addWindowFocusListener(object : java.awt.event.WindowFocusListener {
            override fun windowGainedFocus(e: java.awt.event.WindowEvent?) { onTop() }
            override fun windowLostFocus(e: java.awt.event.WindowEvent?) { if (e?.oppositeWindow?.owner !== window) window.isAlwaysOnTop = false }
        })
    }
    private fun onTop() { window.isAlwaysOnTop = (maximized || fullscreen) && window.isFocused }

    private fun remember() { if (!maximized && !fullscreen) normal = window.bounds }
    fun toggleMaximize() {
        if (fullscreen) return
        if (maximized) { maximized = false; normal?.let { window.bounds = it } } else { remember(); maximized = true; window.bounds = window.graphicsConfiguration.bounds }
        corners(); onTop()
    }
    fun minimize() { window.isAlwaysOnTop = false; (window as? java.awt.Frame)?.extendedState = java.awt.Frame.ICONIFIED }
    fun toggleFullscreen() = changeFullscreen(!fullscreen)
    fun changeFullscreen(on: Boolean) {
        if (on == fullscreen) return
        if (on) { remember(); fullscreen = true; window.bounds = window.graphicsConfiguration.bounds }
        else { fullscreen = false; window.bounds = if (maximized) window.graphicsConfiguration.bounds else normal ?: window.bounds }
        corners(); onTop()
    }
    /** 拖动标题栏移动窗口；最大化时一拖就还原成原来的大小，并让鼠标停在标题栏上相同比例的位置 */
    fun dragBy(start: java.awt.Point, origin: Rectangle, now: java.awt.Point) {
        if (maximized) {
            maximized = false; val size = normal ?: Rectangle(0, 0, 1280, 800)
            val ratio = (start.x - origin.x).toDouble() / origin.width
            window.bounds = Rectangle(now.x - (size.width * ratio).toInt(), now.y - (start.y - origin.y), size.width, size.height); corners(); onTop(); return
        }
        window.setLocation(origin.x + now.x - start.x, origin.y + now.y - start.y)
    }

    /** Windows 11：圆角、深色细边框（旧系统上这些设置无效，也不影响使用） */
    fun corners() {
        if (!custom) return
        runCatching {
            val hwnd = WinDef.HWND(Native.getComponentPointer(window))
            fun set(attribute: Int, value: Int) = Dwm.INSTANCE.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4)
            set(33, if (maximized || fullscreen) 1 else 2)   // 圆角：最大化和全屏时取消
            set(34, 0x00302C2C)                              // 边框颜色（0x00BBGGRR）
            set(20, 1)                                       // 深色模式
        }
    }
    interface Dwm : com.sun.jna.Library {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: IntByReference, size: Int): Int
        companion object { val INSTANCE: Dwm by lazy { Native.load("dwmapi", Dwm::class.java) } }
    }
}

/** 标题栏的空白处：按住拖动窗口，双击最大化 / 还原 */
fun Modifier.windowDrag(chrome: Chrome): Modifier = if (!chrome.custom) this else this.pointerInput(chrome) {
    var lastPress = 0L
    awaitPointerEventScope {
        while (true) {
            val down = awaitPointerEvent()
            if (down.changes.none { it.pressed && !it.previousPressed }) continue
            val now = System.currentTimeMillis()
            if (now - lastPress < 350) { lastPress = 0; chrome.toggleMaximize(); continue }
            lastPress = now
            val start = MouseInfo.getPointerInfo().location; val origin = chrome.window.bounds
            while (true) {
                val event = awaitPointerEvent()
                if (event.changes.none { it.pressed }) break
                val p = MouseInfo.getPointerInfo().location
                if (kotlin.math.abs(p.x - start.x) + kotlin.math.abs(p.y - start.y) > 3) { lastPress = 0; chrome.dragBy(start, origin, p) }
            }
        }
    }
}

enum class WindowButton { MIN, MAX, CLOSE }

/** 右上角的最小化、最大化 / 还原、关闭 */
@Composable
fun WindowButtons(chrome: Chrome, height: Dp = 40.dp, onClose: () -> Unit) {
    if (!chrome.custom || chrome.fullscreen) return
    Row(Modifier.height(height)) {
        WindowButton(WindowButton.MIN, chrome.maximized, height) { chrome.minimize() }
        WindowButton(WindowButton.MAX, chrome.maximized, height) { chrome.toggleMaximize() }
        WindowButton(WindowButton.CLOSE, chrome.maximized, height) { onClose() }
    }
}

@Composable
private fun WindowButton(kind: WindowButton, maximized: Boolean, height: Dp, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }; val hovered by source.collectIsHoveredAsState()
    val close = kind == WindowButton.CLOSE
    Box(Modifier.width(48.dp).fillMaxHeight().background(if (hovered) (if (close) Color.White else C.hover) else Color.Transparent).hoverable(source).clickable(source, null, onClick = onClick), contentAlignment = Alignment.Center) {
        val tint = if (hovered && close) Color.Black else C.ink
        Canvas(Modifier.size(10.dp)) {
            val w = size.width; val stroke = Stroke(1.dp.toPx())
            when (kind) {
                WindowButton.MIN -> drawLine(tint, Offset(0f, w / 2), Offset(w, w / 2), 1.dp.toPx())
                WindowButton.MAX -> if (maximized) {
                    drawRect(tint, Offset(0f, w * .25f), androidx.compose.ui.geometry.Size(w * .75f, w * .75f), style = stroke)
                    drawLine(tint, Offset(w * .25f, w * .25f), Offset(w * .25f, 0f), 1.dp.toPx()); drawLine(tint, Offset(w * .25f, 0f), Offset(w, 0f), 1.dp.toPx()); drawLine(tint, Offset(w, 0f), Offset(w, w * .75f), 1.dp.toPx()); drawLine(tint, Offset(w, w * .75f), Offset(w * .75f, w * .75f), 1.dp.toPx())
                } else drawRect(tint, style = stroke)
                WindowButton.CLOSE -> { drawLine(tint, Offset(0f, 0f), Offset(w, w), 1.2.dp.toPx()); drawLine(tint, Offset(w, 0f), Offset(0f, w), 1.2.dp.toPx()) }
            }
        }
    }
}

/** 没有系统边框后，窗口四边和四角用来拖动改变大小（最大化、全屏时没有） */
@Composable
fun ResizeEdges(chrome: Chrome, minWidth: Int = 980, minHeight: Int = 640) {
    if (!chrome.custom || chrome.maximized || chrome.fullscreen) return
    val e = 5.dp; val c = 12.dp
    Box(Modifier.fillMaxSize()) {
        @Composable fun edge(modifier: Modifier, cursor: Int, dx: Int, dy: Int, dw: Int, dh: Int) = Box(modifier.pointerHoverIcon(PointerIcon(Cursor.getPredefinedCursor(cursor))).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val down = awaitPointerEvent()
                    if (down.changes.none { it.pressed && !it.previousPressed }) continue
                    down.changes.forEach { it.consume() }
                    val start = MouseInfo.getPointerInfo().location; val o = chrome.window.bounds
                    while (true) {
                        val ev = awaitPointerEvent(); if (ev.changes.none { it.pressed }) break
                        ev.changes.forEach { it.consume() }
                        val p = MouseInfo.getPointerInfo().location; val mx = p.x - start.x; val my = p.y - start.y
                        var w = o.width + dw * mx; var h = o.height + dh * my
                        var x = o.x + dx * mx; var y = o.y + dy * my
                        if (w < minWidth) { if (dx != 0) x -= minWidth - w; w = minWidth }
                        if (h < minHeight) { if (dy != 0) y -= minHeight - h; h = minHeight }
                        chrome.window.setBounds(x, y, w, h)
                    }
                }
            }
        })
        edge(Modifier.align(Alignment.CenterStart).width(e).fillMaxHeight(), Cursor.W_RESIZE_CURSOR, 1, 0, -1, 0)
        edge(Modifier.align(Alignment.CenterEnd).width(e).fillMaxHeight(), Cursor.E_RESIZE_CURSOR, 0, 0, 1, 0)
        edge(Modifier.align(Alignment.TopCenter).height(e).fillMaxWidth(), Cursor.N_RESIZE_CURSOR, 0, 1, 0, -1)
        edge(Modifier.align(Alignment.BottomCenter).height(e).fillMaxWidth(), Cursor.S_RESIZE_CURSOR, 0, 0, 0, 1)
        edge(Modifier.align(Alignment.TopStart).size(c), Cursor.NW_RESIZE_CURSOR, 1, 1, -1, -1)
        edge(Modifier.align(Alignment.TopEnd).size(c), Cursor.NE_RESIZE_CURSOR, 0, 1, 1, -1)
        edge(Modifier.align(Alignment.BottomStart).size(c), Cursor.SW_RESIZE_CURSOR, 1, 0, -1, 1)
        edge(Modifier.align(Alignment.BottomEnd).size(c), Cursor.SE_RESIZE_CURSOR, 0, 0, 1, 1)
    }
}
