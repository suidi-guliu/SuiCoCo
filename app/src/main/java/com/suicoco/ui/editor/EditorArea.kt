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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
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
import com.suicoco.core.editor.FindModel
import com.suicoco.core.editor.MinimapModel
import com.suicoco.core.editor.MultiCursorModel
import com.suicoco.core.editor.ReplaceModel
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

    // 文件内查找（v0.3.18）：纯 Kotlin 内核 FindModel 计算匹配区间，状态提升到 EditorArea 顶层，
    // 供查找栏、Ctrl+F 快捷键与 CodeEditor 高亮/跳转共享。全部匹配随文本与选项派生，切换文件自动重算。
    val findModel = remember { FindModel() }
    var findOpen by remember { mutableStateOf(false) }
    var findQuery by remember { mutableStateOf("") }
    var findIgnoreCase by remember { mutableStateOf(true) } // VSCode 默认忽略大小写
    var findWholeWord by remember { mutableStateOf(false) }
    var findIndex by remember { mutableStateOf(-1) } // 当前匹配索引，-1 表示未定位
    var findTick by remember { mutableStateOf(0) } // 触发 CodeEditor 滚动到当前匹配
    val findFocus = remember { FocusRequester() }
    val activeContent = tabs.find { it.path == activePath }?.content.orEmpty()
    val findMatches = remember(activeContent, findQuery, findIgnoreCase, findWholeWord) {
        findModel.findAll(
            activeContent, findQuery,
            FindModel.Options(ignoreCase = findIgnoreCase, wholeWord = findWholeWord)
        )
    }
    // 查找替换（v0.3.19）：复用 FindModel 的匹配结果，ReplaceModel 只负责纯文本替换，
    // 结果经 vm.updateContent 回写，因此撤销树快照、补全刷新、minimap 重算自动同步。
    val replaceModel = remember { ReplaceModel() }
    var replaceOpen by remember { mutableStateOf(false) }
    var replaceText by remember { mutableStateOf("") }
    var replaceNotice by remember { mutableStateOf("") }
    // 查询或选项变化后把当前项重置到首个匹配（空查询为 -1），避免索引指向旧结果。
    LaunchedEffect(findQuery, findIgnoreCase, findWholeWord) {
        findIndex = if (findMatches.isEmpty()) -1 else 0
        replaceNotice = ""
    }
    fun openFind() {
        findOpen = true
        if (findMatches.isNotEmpty() && findIndex < 0) findIndex = 0
    }
    fun openReplace() {
        findOpen = true
        replaceOpen = true
        if (findMatches.isNotEmpty() && findIndex < 0) findIndex = 0
    }
    fun closeFind() { findOpen = false }
    fun findNext() {
        if (findMatches.isEmpty()) return
        findIndex = findModel.nextIndex(findMatches, findIndex)
        findTick++
    }
    fun findPrev() {
        if (findMatches.isEmpty()) return
        findIndex = findModel.previousIndex(findMatches, findIndex)
        findTick++
    }
    fun replaceCurrent() {
        val path = activePath ?: return
        val match = findModel.matchAt(findMatches, findIndex) ?: return
        vm.updateContent(path, replaceModel.replaceAt(activeContent, match.start, match.end, replaceText))
        replaceNotice = "已替换 1 处"
        findTick++
    }
    fun replaceAllMatches() {
        val path = activePath ?: return
        val result = replaceModel.replaceAll(activeContent, findMatches, replaceText)
        replaceNotice = if (result.replaced == 0) "无可替换匹配" else "已替换 ${result.replaced} 处"
        if (result.replaced == 0) return
        vm.updateContent(path, result.text)
        findTick++
    }

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
        // 文件内查找栏（v0.3.18）：Ctrl+F 打开、Esc 关闭，输入即高亮全部匹配并定位首个。
        if (findOpen) {
            FindBar(
                query = findQuery,
                onQueryChange = { findQuery = it },
                matchCount = findMatches.size,
                currentIndex = findIndex,
                ignoreCase = findIgnoreCase,
                wholeWord = findWholeWord,
                focusRequester = findFocus,
                onToggleCase = { findIgnoreCase = !findIgnoreCase },
                onToggleWord = { findWholeWord = !findWholeWord },
                onPrev = { findPrev() },
                onNext = { findNext() },
                onOpenReplace = { openReplace() },
                replaceVisible = replaceOpen,
                replacement = replaceText,
                replaceNotice = replaceNotice,
                onReplacementChange = { replaceText = it; replaceNotice = "" },
                onToggleReplace = { replaceOpen = !replaceOpen },
                onReplaceOne = { replaceCurrent() },
                onReplaceAll = { replaceAllMatches() },
                onClose = { closeFind() }
            )
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
                CodeEditor(
                    tab = tab,
                    vm = vm,
                    cursorModel = cursorModel,
                    multiMode = multiMode,
                    cursorTick = cursorTick,
                    findMatches = findMatches,
                    findIndex = findIndex,
                    findTick = findTick,
                    findModel = findModel,
                    onOpenFind = { openFind() },
                    onOpenReplace = { openReplace() },
                    onFindNext = { findNext() },
                    onFindPrev = { findPrev() },
                    onCloseFind = { closeFind() }
                )
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
    cursorTick: Int,
    findMatches: List<FindModel.Match>,
    findIndex: Int,
    findTick: Int,
    findModel: FindModel,
    onOpenFind: () -> Unit,
    onOpenReplace: () -> Unit,
    onFindNext: () -> Unit,
    onFindPrev: () -> Unit,
    onCloseFind: () -> Unit
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

    // 文件内查找高亮（v0.3.18）：把 FindModel 的匹配区间交给 SelectionHighlightModel 拆成
    // 逐行矩形，复用既有换算口径；当前匹配单独以更亮的颜色叠加。仅视觉指示，不拦截输入。
    val findRects = remember(tab.content, findMatches, charWidthPx, lineHeightPx) {
        if (findMatches.isEmpty()) emptyList()
        else selectionModel.highlightOf(
            tab.content, findModel.asSelectionPairs(findMatches), lineHeightPx, charWidthPx
        )
    }
    val currentFindRect = remember(tab.content, findMatches, findIndex, charWidthPx, lineHeightPx) {
        val match = findModel.matchAt(findMatches, findIndex)
        if (match == null) null
        else selectionModel.highlightOf(
            tab.content, listOf(match.start to match.end), lineHeightPx, charWidthPx
        ).firstOrNull()
    }

    // F3 / Ctrl+F 跳转后滚动到当前匹配所在行：用 CursorLayoutModel 求匹配起点的行号，
    // 换算为像素后平滑滚动；findTick 变化才触发，输入过程（findIndex=0）也会定位首个匹配。
    LaunchedEffect(findTick, findIndex) {
        val match = findModel.matchAt(findMatches, findIndex)
        if (match != null && lineHeightPx > 0f) {
            val line = caretLayout.lineColumnOf(tab.content, match.start).line
            scrollState.animateScrollTo((line * lineHeightPx).toInt().coerceAtLeast(0))
        }
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
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        // 查找快捷键（v0.3.18）：Ctrl+F 打开、F3/Shift+F3 跳转、Esc 关闭，
                        // 均为稳定 Key 常量；输入焦点在文本域或查找栏时均可触发（查找栏另挂同级处理）。
                        when {
                            e.isCtrlPressed && e.key == Key.F -> { onOpenFind(); true }
                            e.isCtrlPressed && e.key == Key.H -> { onOpenReplace(); true }
                            e.key == Key.F3 -> { if (e.isShiftPressed) onFindPrev() else onFindNext(); true }
                            e.key == Key.Escape -> { onCloseFind(); true }
                            e.isCtrlPressed && e.key == Key.Z -> {
                                if (e.isShiftPressed) vm.redo() else vm.undo(); true
                            }
                            e.isCtrlPressed && e.key == Key.Y -> { vm.redo(); true }
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
                        // 文件内查找高亮层（v0.3.18）：所有匹配淡黄底，当前匹配更亮的橙底。
                        // 放在 inner() 之后绘制于文本之上、不放 clickable/pointerInput，不拦截输入。
                        findRects.forEach { r ->
                            Box(
                                modifier = Modifier
                                    .offset(
                                        x = with(density) { (textStartPx + r.xPx).toDp() },
                                        y = with(density) { (textTopPx + r.yPx).toDp() }
                                    )
                                    .width(with(density) { r.widthPx.toDp() })
                                    .height(VscodeTheme.LINE_HEIGHT.dp)
                                    .background(VscodeTheme.Yellow.copy(alpha = 0.20f))
                            )
                        }
                        currentFindRect?.let { r ->
                            Box(
                                modifier = Modifier
                                    .offset(
                                        x = with(density) { (textStartPx + r.xPx).toDp() },
                                        y = with(density) { (textTopPx + r.yPx).toDp() }
                                    )
                                    .width(with(density) { r.widthPx.toDp() })
                                    .height(VscodeTheme.LINE_HEIGHT.dp)
                                    .background(VscodeTheme.Accent.copy(alpha = 0.45f))
                            )
                        }
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

/**
 * 文件内查找栏（v0.3.18）：纯 UI 映射，匹配计算全交由 [FindModel] 内核。
 * - 输入框即时更新查询；↑/↓ 或回车循环跳转上一个/下一个匹配；
 * - 「Aa」切换忽略大小写、「ab」切换全词匹配，选项变化由上层重算匹配并重置当前项；
 * - Esc 关闭查找栏；计数显示为「当前/总数」，无匹配时显示 0/0。
 * 仅用 BasicTextField / Row / Text / clickable / FocusRequester 等稳定 API。
 */
@Composable
private fun FindBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentIndex: Int,
    ignoreCase: Boolean,
    wholeWord: Boolean,
    focusRequester: FocusRequester,
    onToggleCase: () -> Unit,
    onToggleWord: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onOpenReplace: () -> Unit,
    replaceVisible: Boolean,
    replacement: String,
    replaceNotice: String,
    onReplacementChange: (String) -> Unit,
    onToggleReplace: () -> Unit,
    onReplaceOne: () -> Unit,
    onReplaceAll: () -> Unit,
    onClose: () -> Unit
) {
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(VscodeTheme.WidgetBg).padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("🔍", fontSize = 12.sp, color = VscodeTheme.FgMuted)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier.weight(1f).background(VscodeTheme.InputBg).padding(6.dp)
                    .focusRequester(focusRequester)
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        when {
                            e.key == Key.Enter -> { onNext(); true }
                            e.key == Key.F3 -> { if (e.isShiftPressed) onPrev() else onNext(); true }
                            e.isCtrlPressed && e.key == Key.H -> { onOpenReplace(); true }
                            e.key == Key.Escape -> { onClose(); true }
                            else -> false
                        }
                    },
                singleLine = true,
                textStyle = TextStyle(color = VscodeTheme.Fg, fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (matchCount == 0) "0/0" else "${if (currentIndex < 0) 0 else currentIndex + 1}/$matchCount",
                color = VscodeTheme.FgDim, fontSize = 11.sp
            )
            Spacer(Modifier.width(8.dp))
            ToolbarButton("↑", enabled = matchCount > 0, onClick = onPrev)
            ToolbarButton("↓", enabled = matchCount > 0, onClick = onNext)
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            ToolbarButton(
                text = if (ignoreCase) "Aa ✓" else "Aa",
                enabled = true,
                onClick = onToggleCase
            )
            Spacer(Modifier.width(8.dp))
            ToolbarButton(
                text = if (wholeWord) "ab| ✓" else "ab|",
                enabled = true,
                onClick = onToggleWord
            )
            Spacer(Modifier.width(8.dp))
            ToolbarButton(
                text = if (replaceVisible) "替换 ▾" else "替换 ▸",
                enabled = true,
                onClick = onToggleReplace
            )
            Spacer(Modifier.weight(1f))
            ToolbarButton("关闭 Esc", enabled = true, onClick = onClose)
        }
        // 替换行（v0.3.19）：Ctrl+H 打开，Enter 替换当前、按钮「全部」批量替换；
        // 替换串按字面量处理，替换结果经内核回写，撤销树可一次撤销整批替换。
        if (replaceVisible) {
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("↻", fontSize = 12.sp, color = VscodeTheme.FgMuted)
                Spacer(Modifier.width(8.dp))
                BasicTextField(
                    value = replacement,
                    onValueChange = onReplacementChange,
                    modifier = Modifier.weight(1f).background(VscodeTheme.InputBg).padding(6.dp)
                        .onKeyEvent { e ->
                            if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                            when {
                                e.key == Key.Enter -> { onReplaceOne(); true }
                                e.key == Key.Escape -> { onClose(); true }
                                else -> false
                            }
                        },
                    singleLine = true,
                    textStyle = TextStyle(color = VscodeTheme.Fg, fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace)
                )
                Spacer(Modifier.width(8.dp))
                ToolbarButton("替换", enabled = matchCount > 0, onClick = onReplaceOne)
                Spacer(Modifier.width(4.dp))
                ToolbarButton("全部", enabled = matchCount > 0, onClick = onReplaceAll)
                Spacer(Modifier.width(8.dp))
                Text(replaceNotice, color = VscodeTheme.FgDim, fontSize = 11.sp)
            }
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
