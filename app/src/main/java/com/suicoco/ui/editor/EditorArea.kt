package com.suicoco.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suicoco.core.design.VscodeTheme
import com.suicoco.data.EditorTab
import com.suicoco.viewmodel.MainViewModel

@Composable
fun EditorArea(vm: MainViewModel) {
    val tabs by vm.tabs.collectAsState()
    val activePath by vm.activeTabPath.collectAsState()

    Column(modifier = Modifier.fillMaxSize().background(VscodeTheme.EditorBg)) {
        // 标签栏
        Row(
            modifier = Modifier.fillMaxWidth().height(VscodeTheme.TAB_H.dp)
                .background(VscodeTheme.TabInactiveBg)
                .horizontalScroll(rememberScrollState())
        ) {
            tabs.forEach { tab ->
                TabItem(tab, tab.path == activePath, vm)
            }
        }
        // 编辑工具栏：撤销 / 重做（状态由撤销树驱动；Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y 同效）
        val undoState by vm.undoState.collectAsState()
        Row(
            modifier = Modifier.fillMaxWidth().height(28.dp)
                .background(VscodeTheme.TabInactiveBg).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ToolbarButton("↶ 撤销", undoState.first) { vm.undo() }
            Spacer(Modifier.width(12.dp))
            ToolbarButton("↷ 重做", undoState.second) { vm.redo() }
        }
        // 面包屑
        Row(
            modifier = Modifier.fillMaxWidth().height(VscodeTheme.BREADCRUMB_H.dp)
                .background(VscodeTheme.EditorBg).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val tab = tabs.find { it.path == activePath }
            if (tab != null) {
                Text("suicoco-demo", color = VscodeTheme.FgDim, fontSize = 12.sp)
                Text("  >  ", color = VscodeTheme.FgDim, fontSize = 12.sp)
                Text(tab.name, color = VscodeTheme.FgMuted, fontSize = 12.sp)
            }
        }
        // 编辑器主体
        Box(modifier = Modifier.fillMaxSize().weight(1f)) {
            val tab = tabs.find { it.path == activePath }
            if (tab != null) {
                CodeEditor(tab, vm)
                CompletionPopup(vm)
            } else {
                WelcomeScreen(vm)
            }
        }
    }
}

@Composable
private fun TabItem(tab: EditorTab, active: Boolean, vm: MainViewModel) {
    Row(
        modifier = Modifier.height(VscodeTheme.TAB_H.dp)
            .background(if (active) VscodeTheme.TabActiveBg else VscodeTheme.TabInactiveBg)
            .clickable { vm.openFile(tab.path) }
            .padding(start = 12.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (active) {
            Box(Modifier.width(1.dp).height(VscodeTheme.TAB_H.dp)
                .background(VscodeTheme.Accent))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            tab.name + if (tab.dirty) " ●" else "",
            color = if (active) VscodeTheme.Fg else VscodeTheme.FgMuted,
            fontSize = 13.sp
        )
        Spacer(Modifier.width(10.dp))
        Icon(Icons.Default.Close, "关闭", tint = VscodeTheme.FgMuted,
            modifier = Modifier.size(14.dp).clickable { vm.closeTab(tab.path) })
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun CodeEditor(tab: EditorTab, vm: MainViewModel) {
    val completions by vm.completions.collectAsState()
    BasicTextField(
        value = tab.content,
        onValueChange = { vm.updateContent(tab.path, it) },
        modifier = Modifier.fillMaxSize().padding(start = 16.dp, top = 10.dp)
            .onKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onKeyEvent false
                when (e.key) {
                    Key.Z -> { if (e.isShiftPressed) vm.redo() else vm.undo(); true }
                    Key.Y -> { vm.redo(); true }
                    else -> false
                }
            },
        textStyle = TextStyle(
            color = VscodeTheme.Fg,
            fontSize = VscodeTheme.FONT_SIZE.sp,
            lineHeight = VscodeTheme.LINE_HEIGHT.sp,
            fontFamily = FontFamily.Monospace
        ),
        decorationBox = { inner ->
            Box {
                // 行号栏（静态演示 1..N）
                val lineCount = tab.content.count { it == '\n' } + 1
                Column {
                    for (i in 1..lineCount.coerceAtMost(60)) {
                        Text(
                            i.toString().padStart(2),
                            color = VscodeTheme.FgDim,
                            fontSize = VscodeTheme.FONT_SIZE.sp,
                            fontFamily = FontFamily.Monospace,
                            lineHeight = VscodeTheme.LINE_HEIGHT.sp
                        )
                    }
                }
                Spacer(Modifier.width(40.dp))
                inner()
            }
        }
    )
    // 补全弹层触发提示（真实渲染由 CompletionPopup 负责）
}

@Composable
private fun CompletionPopup(vm: MainViewModel) {
    val completions by vm.completions.collectAsState()
    if (completions.isEmpty()) return
    Column(
        modifier = Modifier.width(260.dp)
            .background(VscodeTheme.WidgetBg)
            .padding(4.dp)
            .clickable { }
    ) {
        completions.forEach { item ->
            Row(
                modifier = Modifier.fillMaxWidth().height(24.dp)
                    .padding(horizontal = 6.dp)
                    .clickable { vm.acceptCompletion(item) },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    when (item.kind) { "keyword" -> "🔑"; "snippet" -> "📄"; "function" -> "ƒ"; else -> "▪" },
                    color = VscodeTheme.Purple, fontSize = 12.sp
                )
                Spacer(Modifier.width(8.dp))
                Text(item.label, color = VscodeTheme.Fg, fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace)
                Spacer(Modifier.weight(1f))
                Text(item.detail, color = VscodeTheme.FgDim, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun WelcomeScreen(vm: MainViewModel) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("suicoco", color = VscodeTheme.FgDim.copy(alpha = 0.5f), fontSize = 48.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.Light)
        Spacer(Modifier.height(16.dp))
        Text("从左侧资源管理器打开文件开始编码", color = VscodeTheme.FgDim, fontSize = 14.sp)
        Spacer(Modifier.height(24.dp))
        Row {
            ActionBtn("📁 打开文件") { vm.openQuick(MainViewModel.QuickMode.FILE) }
            Spacer(Modifier.width(12.dp))
            ActionBtn("⚙ 安装环境") { vm.openWizard() }
        }
    }
}

@Composable
private fun ActionBtn(text: String, onClick: () -> Unit) {
    Text(text, color = VscodeTheme.Fg, fontSize = 13.sp,
        modifier = Modifier.background(VscodeTheme.Accent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp))
}

/** 工具栏按钮：enabled=false 时置灰且不响应点击。 */
@Composable
private fun ToolbarButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) VscodeTheme.FgMuted else VscodeTheme.FgDim.copy(alpha = 0.4f),
        fontSize = 12.sp,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}
