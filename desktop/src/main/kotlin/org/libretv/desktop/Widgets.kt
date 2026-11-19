package org.libretv.desktop

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 圆角输入框；回车触发 onSubmit */
@Composable
fun Input(value: String, onChange: (String) -> Unit, hint: String, modifier: Modifier = Modifier, leading: Icon? = null, height: Dp = 44.dp, onSubmit: () -> Unit = {}) {
    BasicTextField(value, onChange, modifier.height(height).onPreviewKeyEvent { if (it.key == Key.Enter && it.type == androidx.compose.ui.input.key.KeyEventType.KeyDown) { onSubmit(); true } else false },
        singleLine = true, textStyle = TextStyle(color = C.ink, fontSize = 15.sp), cursorBrush = SolidColor(C.ink),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { onSubmit() }),
        decorationBox = { field ->
            Row(Modifier.fillMaxSize().clip(RoundedCornerShape(height / 2)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(height / 2)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                if (leading != null) { IconView(leading, C.muted, 18.dp); Spacer(Modifier.width(10.dp)) }
                Box(Modifier.weight(1f)) { if (value.isEmpty()) T(hint, 15.sp, C.faint, lines = 1); field() }
            }
        })
}

/** 开关 */
@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) {
    val track by animateColorAsState(if (on) Color.White else C.hover)
    val x by animateDpAsState(if (on) 20.dp else 2.dp)
    Box(Modifier.size(44.dp, 26.dp).clip(RoundedCornerShape(13.dp)).background(track).pointerHoverIcon(PointerIcon.Hand).clickable { onChange(!on) }) {
        Box(Modifier.offset(x = x, y = 2.dp).size(22.dp).clip(CircleShape).background(if (on) Color.Black else C.muted))
    }
}

/** 设置页里的一行：标题、说明、右侧控件；可整行点击 */
@Composable
fun SettingRow(title: String, subtitle: String = "", onClick: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    val source = remember { MutableInteractionSource() }; val hovered by source.collectIsHoveredAsState()
    Row(Modifier.fillMaxWidth().background(if (hovered && onClick != null) C.raised else Color.Transparent).hoverable(source)
        .then(if (onClick != null) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(source, null) { onClick() } else Modifier)
        .padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            T(title, 15.sp, lines = 2)
            if (subtitle.isNotBlank()) { Spacer(Modifier.height(3.dp)); T(subtitle, 12.sp, C.muted, lines = 2) }
        }
        Spacer(Modifier.width(12.dp)); trailing()
    }
}

/** 一组设置行，圆角卡片里用细线分隔 */
@Composable
fun Group(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        if (title.isNotEmpty()) T(title, 12.sp, C.muted, FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
        Panel(Modifier.fillMaxWidth()) { Column { content() } }
    }
}
@Composable
fun Divider() = Box(Modifier.fillMaxWidth().padding(start = 18.dp).height(1.dp).background(C.line))

/** 对话框：遮罩上的面板；点遮罩或「取消」关闭 */
class DialogState {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
    fun show(block: @Composable () -> Unit) { content = block }
    fun close() { content = null }
    fun confirm(title: String, message: String, action: String, onConfirm: () -> Unit) = show {
        Column(Modifier.width(420.dp).padding(24.dp)) {
            T(title, 18.sp, weight = FontWeight.Bold); Spacer(Modifier.height(10.dp)); T(message, 14.sp, C.muted, lineHeight = 21.sp); Spacer(Modifier.height(22.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { Pill("取消") { close() }; Spacer(Modifier.width(10.dp)); Pill(action, primary = true) { close(); onConfirm() } }
        }
    }
}
@Composable
fun DialogHost(state: DialogState) {
    // 关闭时内容保留到淡出结束
    var last by remember { mutableStateOf<(@Composable () -> Unit)?>(null) }
    state.content?.let { last = it }
    androidx.compose.animation.AnimatedVisibility(state.content != null, enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)), exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150))) {
        Box(Modifier.fillMaxSize().background(Color(0xB0000000)).clickable(remember { MutableInteractionSource() }, null) { state.close() }, contentAlignment = Alignment.Center) {
            Box(Modifier.animateEnterExit(enter = androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(200), initialScale = .94f), exit = androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(150), targetScale = .96f))
                .widthIn(max = 720.dp).clip(RoundedCornerShape(20.dp)).background(C.surface).border(1.dp, C.line, RoundedCornerShape(20.dp)).clickable(remember { MutableInteractionSource() }, null) {}) { last?.invoke() }
        }
    }
}
