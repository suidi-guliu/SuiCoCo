package com.suicoco.ui.editor

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suicoco.core.design.VscodeTheme
import com.suicoco.core.editor.CursorLayoutModel
import com.suicoco.core.editor.MinimapModel
import com.suicoco.core.editor.MultiCursorModel
import com.suicoco.core.editor.SelectionHighlightModel
import com.suicoco.data.EditorTab
import com.suicoco.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun EditorArea(vm: MainViewModel) {
    val tabs by vm.tabs.collectAsState()
    val activePath by vm.activeTabPath.collectAsState()

    // 多光标模式：内核实例与开关状态在重组间保持。内核的批量编辑经 vm.updateContent 回写，
    // 因此与撤销树、补全、minimap 既有行为天然同步；纯 Kotlin 内核可独立单测。
    val cursorModel = remember { MultiCursorModel() }
    var multiMode by remember { mutableStateOf(false) }
    var cursorTick by remember { mutableStateOf(0) } // 光标数变化触发重组

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
            Spacer(Modifier.width(16.dp))
            ToolbarButton(
                text = if (multiMode) "⋮ 多光标 ON (${cursorModel.cursorCount()})" else "⋮ 多光标",
                enabled = true
            ) {
                multiMode = !multiMode
                cursorModel.clearCursors()
                cursorTick++
            }
        }
        // 多光标操作条：仅开启时显示，复用内核的列编辑放置与批量编辑能力。
        if (multiMode) {
            MultiCursorBar(
                vm = vm,
                path = activePath.orEmpty(),
                model = cursorModel,
                content = tabs.find { it.path == activePath }?.content.orEmpty(),
                onTick = { cursorTick++ },
                onExit = { multiMode = false }
            )
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
                CodeEditor(tab, vm, cursorModel, multiMode, cursorTick)
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
private fun CodeEditor(
    tab: EditorTab,
    vm: MainViewModel,
    cursorModel: MultiCursorModel,
    multiMode: Boolean,
    cursorTick: Int
) {
    // 滚动状态提升到 CodeEditor：BasicTextField 走系统自带滚动，外层 Box 的 verticalScroll
    // 仅作为「按行跳转」的可控代理（把滚动锚点换算为像素后 animateScrollTo）。
    // 说明：BasicTextField 内部自行滚动且旧的 value/onValueChange 重载不暴露 scrollState，
    // 故跳转通过外层可滚动容器 + 行高像素换算实现，与键盘输入/撤销重做互不影响。
    val scrollState = rememberScrollState()
    val density = LocalDensity.current
    val lineHeightPx = with(density) { VscodeTheme.LINE_HEIGHT.sp.toPx() }
    val sourceLineCount = tab.content.count { it == '\n' } + 1
    // 视口可见行数：以代码区当前高度 / 行高估算，至少 1 行。
    var viewportPx by remember { mutableStateOf(0f) }
    val visibleLines = if (lineHeightPx > 0f && viewportPx > 0f)
        (viewportPx / lineHeightPx).toInt().coerceAtLeast(1) else 1

    // 逐光标 caret 定位：把 MultiCursorModel 的文本偏移经纯 Kotlin 内核 CursorLayoutModel
    // 换算为像素坐标，在文本域叠加多条竖线，让「每个光标落在哪一行哪一列」可见。
    // 等宽字体列宽按字号近似（与 minimap 行高估算同口径），仅作视觉指示，不影响编辑语义。
    val charWidthPx = with(density) { (VscodeTheme.FONT_SIZE * 0.6f).sp.toPx() }
    val textStartPx = with(density) { 16.dp.toPx() }
    val textTopPx = with(density) { 10.dp.toPx() }
    val caretLayout = remember { CursorLayoutModel() }
    val caretPoints = remember(tab.content, cursorTick, multiMode, charWidthPx, lineHeightPx) {
        if (multiMode && cursorModel.cursorCount() > 1)
            caretLayout.carets(tab.content, cursorModel.cursors(), lineHeightPx, charWidthPx)
        else emptyList()
    }

    // 逐光标多段选区高亮（v0.3.17）：取主光标下的标识符 token，用纯 Kotlin 内核
    // SelectionHighlightModel 找出它在全文的全部出现并拆成逐行高亮矩形，在文本域叠加
    // 半透明选区块——VSCode「选中所有匹配项」的视觉。仅多光标模式且有光标时显示，
    // 与 caret 竖线、批量编辑、撤销、补全、minimap 互不干扰；内核零依赖、可独立单测。
    val selectionModel = remember { SelectionHighlightModel() }
    val selectionRects = remember(tab.content, cursorTick, multiMode, charWidthPx, lineHeightPx) {
        if (multiMode && cursorModel.cursorCount() > 0) {
            val token = selectionModel.tokenRangeOf(tab.content, cursorModel.primaryCursor())
            if (token.first < token.second) {
                val name = tab.content.substring(token.first, token.second)
                val occ = selectionModel.occurrencesOf(tab.content, name)
                selectionModel.highlightRects(tab.content, occ, lineHeightPx, charWidthPx)
            } else emptyList()
        } else emptyList()
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().weight(1f)
                .onSizeChanged { viewportPx = it.height.toFloat() }
                .verticalScroll(scrollState)
        ) {
            BasicTextField(
                value = tab.content,
                onValueChange = { vm.updateContent(tab.path, it) },
                // fillMaxWidth（非 fillMaxSize）：文本按内容高度铺开，滚动交给外层 verticalScroll，
                // 这样 scrollState 才有真实 maxValue，minimap 的 animateScrollTo 才能定位目标行。
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 10.dp)
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
                        Column {
                            for (i in 1..sourceLineCount.coerceAtMost(60)) {
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
                        // 多段选区高亮层（v0.3.17）：逐行半透明矩形，坐标口径与文本 padding 对齐。
                        // 仅视觉指示，不放 clickable/pointerInput，不拦截键盘与触摸输入。
                        selectionRects.forEach { r ->
                            Box(
                                modifier = Modifier
                                    .offset(
                                        x = with(density) { (textStartPx + r.xPx).toDp() },
                                        y = with(density) { (textTopPx + r.yPx).toDp() }
                                    )
                                    .width(with(density) { r.widthPx.toDp() })
                                    .height(VscodeTheme.LINE_HEIGHT.dp)
                                    .background(VscodeTheme.Purple.copy(alpha = 0.24f))
                            )
                        }
                        // 多光标 caret 叠加层：逐光标竖线（内核算行列→像素，UI 只做定位绘制）。
                        // 使用 Box + offset + background 等稳定 API，坐标口径与文本 padding 对齐。
                        caretPoints.forEach { cp ->
                            Box(
                                modifier = Modifier
                                    .offset(
                                        x = with(density) { (textStartPx + cp.xPx).toDp() },
                                        y = with(density) { (textTopPx + cp.yPx).toDp() }
                                    )
                                    .width(2.dp)
                                    .height(VscodeTheme.LINE_HEIGHT.dp)
                                    .background(VscodeTheme.Purple)
                            )
                        }
                    }
                }
            )
        }
        // 右侧代码缩略图（minimap）：点击/拖拽源行号 → 滚动锚点 → 平滑滚动到目标行。
        MinimapRail(
            content = tab.content,
            scrollState = scrollState,
            visibleLines = visibleLines,
            lineHeightPx = lineHeightPx
        )
    }
    // 补全弹层触发提示（真实渲染由 CompletionPopup 负责）
}

/**
 * 代码缩略图竖条：把每行渲染为一条极细横杠，密度越高越亮、注释偏黄、空行跳过。
 * 计算全部交由纯 Kotlin 内核 [MinimapModel]，UI 只做映射，无状态、可幂等重组。
 *
 * v0.3.14 接线：点击某行横杠 → [MinimapModel.lineAt] 把点击行换算为源行号（截断时按比例放大）
 * → [MinimapModel.scrollAnchorLine] 求滚动锚点（目标行居中）→ [ScrollState.animateScrollTo]
 * 平滑滚动编辑器到目标行；同时用 [MinimapModel.viewportRect] 叠加视口高亮框。
 * v0.3.16 接线：在整条 minimap 上按住拖拽 → [MinimapModel.scrollAnchorFromDrag] 把连续 y
 * （保留小数、避免跳格）换算为滚动锚点 → [ScrollState.scrollTo] 即时跟手滚动；点击跳转保留。
 * 仅用 pointerInput / detectVerticalDragGestures / clickable / Box 等稳定 API，不引入实验性手势接口。
 */
@Composable
private fun MinimapRail(
    content: String,
    scrollState: ScrollState,
    visibleLines: Int,
    lineHeightPx: Float
) {
    val model = remember { MinimapModel() }
    val layout = remember(content) { model.build(content) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // 缩略列单行像素高（= DEFAULT_ROW_PX.dp）与顶部 padding：把拖拽 y 换算为「解析行」单位
    val rowPxPx = with(density) { MinimapModel.DEFAULT_ROW_PX.dp.toPx() }
    val topPadPx = with(density) { 10.dp.toPx() }

    // 视口高亮：以当前滚动像素反推首可见行（行高 >0 时），仅作视觉指示，不影响跳转。
    val firstVisible = if (lineHeightPx > 0f) (scrollState.value / lineHeightPx).toInt() else 0
    val viewport = remember(layout, firstVisible, visibleLines) {
        model.viewportRect(layout, firstVisible, visibleLines)
    }

    // 点击解析行 → 源行号 → 滚动锚点 → 平滑滚动。lineAt 对截断布局按比例还原源行号。
    fun jumpToLine(parsedIndex: Int) {
        if (lineHeightPx <= 0f || layout.lineCount <= 0) return
        val yInLines = parsedIndex.toFloat()
        val targetLine = model.lineAt(layout, yInLines)
        val anchor = model.scrollAnchorLine(layout, targetLine, visibleLines)
        val targetPx = (anchor * lineHeightPx).toInt().coerceAtLeast(0)
        scope.launch { scrollState.animateScrollTo(targetPx) }
    }

    // 拖拽连续滚动（v0.3.16）：指针 y（相对 minimap 内容顶部，扣除 top padding）→ 连续解析行
    // → scrollAnchorFromDrag 求居中锚点 → scrollTo 即时定位。detectVerticalDragGestures 为
    // Compose Foundation 稳定 API；onVerticalDrag 直接吃 change.position.y（绝对位置，避免累加漂移）。
    fun dragTo(yPx: Float) {
        if (lineHeightPx <= 0f || rowPxPx <= 0f || layout.lineCount <= 0) return
        val yInLines = (yPx - topPadPx) / rowPxPx
        val anchor = model.scrollAnchorFromDrag(layout, yInLines, visibleLines)
        val targetPx = (anchor * lineHeightPx).toInt().coerceAtLeast(0)
        scope.launch { scrollState.scrollTo(targetPx) }
    }

    Box(
        modifier = Modifier.fillMaxHeight().width(48.dp)
            .background(VscodeTheme.EditorBg)
            // pointerInput 置于 padding 之前（更外层）：position.y 相对整条 rail 顶部，
            // 故 dragTo 需扣除 topPadPx 才对准第 0 行；拖拽热区也覆盖整条 rail 更易抓取。
            .pointerInput(layout, lineHeightPx, rowPxPx, topPadPx, visibleLines) {
                detectVerticalDragGestures(
                    onDragStart = { dragTo(it.y) },
                    onVerticalDrag = { change, _ -> dragTo(change.position.y) }
                )
            }
            .padding(top = 10.dp, end = 2.dp)
    ) {
        Column {
            layout.lines.forEach { line ->
                if (line.density > 0) {
                    val barColor = when (line.kind) {
                        MinimapModel.Kind.COMMENT -> VscodeTheme.Yellow
                        MinimapModel.Kind.CODE -> VscodeTheme.FgMuted
                        MinimapModel.Kind.BLANK -> VscodeTheme.EditorBg
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(MinimapModel.DEFAULT_ROW_PX.dp)
                            .padding(start = (line.indent.coerceAtMost(12) * 1).dp)
                            .background(barColor.copy(alpha = 0.25f + line.density * 0.18f))
                            .clickable { jumpToLine(line.index) }
                    )
                } else {
                    Spacer(
                        Modifier.fillMaxWidth().height(MinimapModel.DEFAULT_ROW_PX.dp)
                            .clickable { jumpToLine(line.index) }
                    )
                }
            }
        }
        // 视口高亮框：覆盖当前可见行区间（行 × 2.dp），纯视觉指示，不拦截点击。
        if (viewport.heightLines > 0f) {
            val topDp = (viewport.topLine * MinimapModel.DEFAULT_ROW_PX).coerceAtLeast(0f).dp
            val heightDp = (viewport.heightLines * MinimapModel.DEFAULT_ROW_PX).coerceAtLeast(0f).dp
            Box(
                modifier = Modifier.fillMaxWidth().height(heightDp)
                    .offset(y = topDp)
                    .background(VscodeTheme.Accent.copy(alpha = 0.10f))
            )
        }
    }
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

/**
 * 多光标操作条：列编辑放置 + 批量编辑，全部交由纯 Kotlin 内核 [MultiCursorModel] 完成，
 * UI 只做按钮映射与回写。BasicTextField 原生不支持多光标文本域，故采用「缓冲输入 +
 * 批量应用」交互：先在同列放置若干光标，再把缓冲文本一次性插入/退格/删除到所有光标处。
 * 完整的多光标文本域（逐光标选区渲染）属后续阶段，见 ARCHITECTURE §7。
 */
@Composable
private fun MultiCursorBar(
    vm: MainViewModel,
    path: String,
    model: MultiCursorModel,
    content: String,
    onTick: () -> Unit,
    onExit: () -> Unit
) {
    var buffer by remember { mutableStateOf("    ") }
    val applyEdit: (MultiCursorModel.EditResult) -> Unit = { result ->
        if (path.isNotEmpty() && result.text != content) vm.updateContent(path, result.text)
        onTick()
    }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(VscodeTheme.WidgetBg).padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("⋮ 多光标(${model.cursorCount()})", color = VscodeTheme.Purple, fontSize = 12.sp)
            Spacer(Modifier.width(10.dp))
            ToolbarButton("＋下行同列") {
                val off = if (model.cursorCount() == 0) 0 else model.primaryCursor()
                val next = model.columnDown(content, off)
                if (model.addCursor(next, content.length)) onTick()
            }
            Spacer(Modifier.width(8.dp))
            ToolbarButton("＋上行同列") {
                val off = if (model.cursorCount() == 0) 0 else model.primaryCursor()
                val prev = model.columnUp(content, off)
                if (model.addCursor(prev, content.length)) onTick()
            }
            Spacer(Modifier.width(8.dp))
            ToolbarButton("清空") { model.clearCursors(); onTick() }
            Spacer(Modifier.width(8.dp))
            ToolbarButton("退出") { onExit() }
        }
        Spacer(Modifier.height(3.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicTextField(
                value = buffer,
                onValueChange = { buffer = it },
                modifier = Modifier.weight(1f).background(VscodeTheme.InputBg).padding(6.dp),
                singleLine = true,
                textStyle = TextStyle(color = VscodeTheme.Fg, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.width(8.dp))
            ToolbarButton("插入") { applyEdit(model.insertAtAll(content, buffer)) }
            Spacer(Modifier.width(8.dp))
            ToolbarButton("退格") { applyEdit(model.deleteBeforeAll(content, 1)) }
            Spacer(Modifier.width(8.dp))
            ToolbarButton("删除") { applyEdit(model.deleteAfterAll(content, 1)) }
        }
    }
}

/** 工具栏按钮：enabled=false 时置灰且不响应点击。 */
@Composable
private fun ToolbarButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Text(
        text,
        color = if (enabled) VscodeTheme.FgMuted else VscodeTheme.FgDim.copy(alpha = 0.4f),
        fontSize = 12.sp,
        modifier = Modifier.clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 3.dp)
    )
}
