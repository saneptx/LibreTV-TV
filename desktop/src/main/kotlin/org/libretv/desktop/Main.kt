package org.libretv.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.io.File

val LocalModel = staticCompositionLocalOf<AppModel> { error("no model") }
val LocalDialog = staticCompositionLocalOf<DialogState> { error("no dialog") }
val LocalWindow = staticCompositionLocalOf<WindowState> { error("no window") }
val LocalChrome = staticCompositionLocalOf<Chrome> { error("no chrome") }
/** 关闭窗口（按「关闭窗口后在后台继续运行」的设置决定隐藏还是退出） */
val LocalClose = staticCompositionLocalOf<() -> Unit> { {} }

/** 数据目录：Windows 在 %LOCALAPPDATA%\SuperTV，macOS 在 ~/Library/Application Support/SuperTV */
fun dataHome(): File = System.getenv("SUPERTV_HOME")?.let { File(it) } ?: if (LocalService.windows) File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "SuperTV")
    else File(System.getProperty("user.home"), "Library/Application Support/SuperTV")

fun main() {
    System.setProperty("skiko.renderApi", System.getProperty("skiko.renderApi") ?: if (LocalService.windows) "DIRECT3D" else "METAL")
    val resources = System.getProperty("compose.application.resources.dir")?.let { File(it) }?.takeIf { it.isDirectory }
    val home = dataHome().apply { mkdirs() }
    Startup.begin(home)
    val model = AppModel(home, resources)
    Startup.mark("数据层已创建")
    Vlc.warmUp()
    application {
        var visible by remember { mutableStateOf(true) }
        val state = rememberWindowState(size = DpSize(1360.dp, 860.dp))
        val icon = painterResource("supertv.png")
        fun quit() { model.release(); exitApplication() }
        // 关闭窗口后串流服务仍在后台运行（电视要用），从托盘图标重新打开或退出
        androidx.compose.runtime.DisposableEffect(Unit) {
            val tray = SystemTray(onOpen = { visible = true }, onQuit = { quit() })
            onDispose { tray.remove() }
        }
        fun close() { if (model.service.owned && model.service.keepRunning) { visible = false; model.toast("SuperTV 在后台继续为电视提供串流服务") } else quit() }
        // Windows 上不用系统标题栏和边框，由程序自己画（macOS 保留系统的红绿灯按钮）
        val custom = LocalService.windows
        Window(onCloseRequest = ::close, visible = visible, state = state, title = "SuperTV", icon = icon, undecorated = custom) {
            window.minimumSize = java.awt.Dimension(980, 640)
            window.background = java.awt.Color.BLACK
            val dialog = remember { DialogState() }
            val chrome = remember { Chrome(window, custom) }
            androidx.compose.runtime.LaunchedEffect(Unit) { Startup.mark("窗口第一帧"); chrome.corners() }
            androidx.compose.runtime.LaunchedEffect(model.ready) { if (model.ready) Startup.mark("资料已就绪") }
            CompositionLocalProvider(LocalModel provides model, LocalDialog provides dialog, LocalWindow provides state, LocalChrome provides chrome, LocalClose provides ::close) {
                Box(Modifier.fillMaxSize()) { App(model, dialog); ResizeEdges(chrome) }
            }
        }
    }
}

@Composable
fun App(model: AppModel, dialog: DialogState) {
    Box(Modifier.fillMaxSize().background(C.bg)) {
        val screen = model.screen
        // 进出播放页淡入淡出；其他页面之间切换时淡入并轻微平移（前进从右边进，返回从左边进）
        var depth by remember { mutableStateOf(model.stack.size) }
        val forward = model.stack.size >= depth
        androidx.compose.runtime.SideEffect { depth = model.stack.size }
        androidx.compose.animation.Crossfade(screen is Screen.Player, animationSpec = androidx.compose.animation.core.tween(260)) { playing ->
            if (playing) (model.screen as? Screen.Player)?.let { PlayerScreen(it) }
            else Column(Modifier.fillMaxSize()) {
                TopBar(model)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    if (!model.ready) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { T(model.bootError ?: "正在加载…", 15.sp, C.muted) }
                    else androidx.compose.animation.AnimatedContent(model.screen.takeIf { it !is Screen.Player } ?: Screen.Home, transitionSpec = {
                        val shift = if (forward) 1 else -1
                        (fadeIn(androidx.compose.animation.core.tween(240, 40)) + androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(280)) { shift * it / 28 }) togetherWith
                            fadeOut(androidx.compose.animation.core.tween(140))
                    }) { target ->
                        when (target) {
                            Screen.Home -> HomeScreen()
                            Screen.Library -> LibraryScreen()
                            Screen.Downloads -> DownloadsScreen()
                            Screen.Settings -> SettingsScreen()
                            is Screen.Search -> SearchScreen(target.query)
                            is Screen.Detail -> DetailScreen(target.card)
                            else -> {}
                        }
                    }
                }
            }
        }
        DialogHost(dialog)
        AnimatedVisibility(model.toast != null, Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp), enter = fadeIn(), exit = fadeOut()) {
            Box(Modifier.clip(RoundedCornerShape(22.dp)).background(Color(0xF0222224)).padding(horizontal = 20.dp, vertical = 12.dp)) { T(model.toast.orEmpty(), 14.sp) }
        }
    }
}

@Composable
private fun TopBar(model: AppModel) {
    var query by remember { mutableStateOf("") }
    val screen = model.screen; val chrome = LocalChrome.current; val close = LocalClose.current
    // 顶栏兼作标题栏：空白处可以拖动窗口、双击最大化；右上角是窗口按钮
    Row(Modifier.fillMaxWidth().height(68.dp).background(C.bar).windowDrag(chrome).padding(start = 20.dp, end = if (chrome.custom) 0.dp else 20.dp), verticalAlignment = Alignment.CenterVertically) {
        if (model.stack.size > 1) { IconButton(Icon.BACK) { model.back() }; Spacer(Modifier.width(6.dp)) }
        Row(Modifier.press { model.tab(Screen.Home) }, verticalAlignment = Alignment.CenterVertically) {
            Logo(30.dp); Spacer(Modifier.width(10.dp)); T("SuperTV", 20.sp, weight = FontWeight.Bold)
        }
        Spacer(Modifier.width(28.dp))
        Row(Modifier.clip(RoundedCornerShape(22.dp)).background(C.surface).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            listOf(Screen.Home to "首页", Screen.Library to "片库", Screen.Downloads to "电脑片库").forEach { (target, label) ->
                val on = model.stack.firstOrNull() == target && model.stack.size == 1
                val tabColor by androidx.compose.animation.animateColorAsState(if (on) C.hover else Color.Transparent, androidx.compose.animation.core.tween(200))
                Box(Modifier.clip(RoundedCornerShape(18.dp)).background(tabColor).press { model.tab(target) }.padding(horizontal = 18.dp, vertical = 8.dp)) {
                    T(label, 14.sp, if (on) C.ink else C.muted, FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        Input(query, { query = it }, "搜索电影、剧集、综艺", Modifier.width(320.dp), leading = Icon.SEARCH, height = 40.dp) {
            val q = query.trim(); if (q.isNotEmpty()) { if (screen is Screen.Search) model.back(); model.open(Screen.Search(q)) }
        }
        Spacer(Modifier.width(10.dp))
        ServiceDot(model)
        IconButton(Icon.SETTINGS, filled = screen == Screen.Settings) { if (screen != Screen.Settings) model.open(Screen.Settings) }
        if (chrome.custom) { Spacer(Modifier.width(12.dp)); Box(Modifier.align(Alignment.Top)) { WindowButtons(chrome, 40.dp, close) } }
    }
}

/** 顶栏的小圆点：串流服务的状态，点开去设置 */
@Composable
private fun ServiceDot(model: AppModel) {
    val s = model.service
    val (color, text) = when (s.state) {
        LocalService.State.RUNNING, LocalService.State.EXTERNAL -> Color(0xFFE5E5EA) to "串流服务运行中"
        LocalService.State.STARTING -> C.muted to "正在启动串流服务"
        else -> C.faint to "串流服务未运行"
    }
    Row(Modifier.clip(RoundedCornerShape(16.dp)).press { model.open(Screen.Settings); settingsTab = "service" }.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(8.dp).height(8.dp).clip(RoundedCornerShape(4.dp)).background(color)); Spacer(Modifier.width(8.dp)); T(text, 12.sp, C.muted)
    }
}
