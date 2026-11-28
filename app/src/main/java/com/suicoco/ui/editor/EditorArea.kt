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
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suicoco.core.design.VscodeTheme
import com.suicoco.core.editor.AutoIndentModel
import com.suicoco.core.editor.BlockCommentModel
import com.suicoco.core.editor.BracketMatchModel
import com.suicoco.core.editor.CaseModel
import com.suicoco.core.editor.CommentModel
import com.suicoco.core.editor.CursorLayoutModel
import com.suicoco.core.editor.FindModel
import com.suicoco.core.editor.FoldingModel
import com.suicoco.core.editor.GoToLineModel
import com.suicoco.core.editor.IndentGuideModel
import com.suicoco.core.editor.IndentSelectionModel
import com.suicoco.core.editor.IndentationConvertModel
import com.suicoco.core.editor.LineOperationsModel
import com.suicoco.core.editor.MinimapModel
import com.suicoco.core.editor.MultiCursorModel
import com.suicoco.core.editor.ReplaceModel
import com.suicoco.core.editor.SortLinesModel
import com.suicoco.core.editor.AutoPairModel
import com.suicoco.core.editor.SelectionHighlightModel
import com.suicoco.core.editor.SymbolOutlineModel
import com.suicoco.core.editor.WordMovementModel
import com.suicoco.data.EditorTab
import com.suicoco.viewmodel.MainViewModel
import kotlinx.coroutines.launch

// 文本工具快捷栏动作（v0.3.36）：缩进风格转换与选区行排序共用一套请求通道。
private const val TEXT_TO_SPACES = 0
private const val TEXT_TO_TABS = 1
private const val TEXT_SORT_ASC = 2
private const val TEXT_SORT_DESC = 3

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
    // 代码折叠（v0.3.21）：纯 Kotlin FoldingModel 识别可折叠区间，折叠/展开结果经
    // vm.updateContent 写回，撤销树自动同步。foldTick 在每次折叠/展开后自增，触发行号栏重组。
    val foldingModel = remember { FoldingModel() }
    var foldTick by remember { mutableStateOf(0) }
    // 行注释切换（v0.3.22）：纯 Kotlin CommentModel 按文件类型推断行注释符号，
    // 对光标行或选区涉及的多行统一加/去注释，结果经 vm.updateContent 写回，撤销树整步回退。
    val commentModel = remember { CommentModel() }
    var commentRequest by remember { mutableStateOf(0) } // 工具栏触发行注释切换的递增计数
    // 代码大纲（v0.3.23）：纯 Kotlin SymbolOutlineModel 词法扫描声明符号，面板与跳转共享。
    // 由 activeContent 派生，切换文件/编辑内容自动重算；outlineJumpTick 触发 CodeEditor 滚动定位。
    val outlineModel = remember { SymbolOutlineModel() }
    var outlineOpen by remember { mutableStateOf(false) }
    var outlineJumpLine by remember { mutableStateOf(-1) }
    var outlineJumpTick by remember { mutableStateOf(0) }
    val outlineSymbols = remember(activeContent) { outlineModel.symbolsOf(activeContent) }
    // 自动缩进（v0.3.24）：纯 Kotlin AutoIndentModel 在回车/Tab/Shift+Tab 时计算新文本与光标，
    // 经 vm.updateContent 回写，撤销树整步回退、补全/minimap/查找高亮自动同步。
    val autoIndentModel = remember { AutoIndentModel() }
    // 跳转到指定行（v0.3.25）：纯 Kotlin GoToLineModel 解析「行[:列]」并按文档行数/行尾夹紧，
    // 只产出光标偏移；滚动复用查找/大纲同款「偏移→行→像素」锚点，编辑语义完全不变。
    val gotoModel = remember { GoToLineModel() }
    var gotoOpen by remember { mutableStateOf(false) }
    var gotoQuery by remember { mutableStateOf("") }
    var gotoNotice by remember { mutableStateOf("") }
    var gotoOffset by remember { mutableStateOf(-1) }
    var gotoTick by remember { mutableStateOf(0) } // 递增触发 CodeEditor 定位
    val gotoFocus = remember { FocusRequester() }
    // 行操作（v0.3.26）：纯 Kotlin LineOperationsModel 负责移动/复制/删除行，
    // 结果经 vm.updateContent 回写，撤销树整步回退、补全/minimap/查找高亮自动同步。
    // 工具栏按钮通过 request 递增 + kind 传入 CodeEditor，与快捷键共用同一执行函数。
    val lineOpsModel = remember { LineOperationsModel() }
    var lineOpRequest by remember { mutableStateOf(0) }
    var lineOpKind by remember { mutableStateOf(LineOperationsModel.MOVE_UP) }
    fun requestLineOp(kind: Int) { lineOpKind = kind; lineOpRequest++ }
    // 大小写转换（v0.3.28）：纯 Kotlin CaseModel 对选区（或光标词）做 7 种转换，
    // 工具栏「Aa 大小写」展开快捷栏，Ctrl+Shift+U/L 直接转大写/小写；
    // 结果经 vm.updateContent 回写，撤销树整步回退、补全/minimap/查找高亮自动同步。
    val caseModel = remember { CaseModel() }
    var caseOpen by remember { mutableStateOf(false) }
    var caseRequest by remember { mutableStateOf(0) }
    var caseKind by remember { mutableStateOf(CaseModel.UPPER) }
    fun requestCase(kind: Int) { caseKind = kind; caseRequest++ }
    // 缩进指南线（v0.3.30）：纯 Kotlin IndentGuideModel 按前导空白算每行 guide 列，
    // 相邻行同列合并为连续竖线；不写回缓冲区，仅作视觉指示，随内容重组自动刷新，不拦截输入。
    val indentGuideModel = remember { IndentGuideModel() }
    // 按词移动/删除（v0.3.32）：纯 Kotlin WordMovementModel 负责词边界与目标偏移，
    // 移动仅改光标选区、删除经 vm.updateContent 回写，撤销树整步回退、补全/minimap/查找高亮自动同步。
    val wordModel = remember { WordMovementModel() }
    var wordRequest by remember { mutableStateOf(0) }
    var wordKind by remember { mutableStateOf(WordMovementModel.MOVE_LEFT) }
    fun requestWord(kind: Int) { wordKind = kind; wordRequest++ }
    // 自动配对括号/引号（v0.3.34）：纯 Kotlin AutoPairModel 比较 onValueChange 的 old/new
    // 推断单字符插入/选区包裹并补右符号，普通 Backspace 成对删除；结果经 vm.updateContent
    // 回写，撤销树整步回退、补全/minimap/查找高亮自动同步。与 BracketMatchModel（跳转/高亮）正交。
    val autoPairModel = remember { AutoPairModel() }
    // 多行选区整体缩进/反缩进（v0.3.35）：有选区的 Tab/Shift+Tab 走 IndentSelectionModel 块缩进，
    // 无选区保留 AutoIndentModel 单行自动缩进；工具栏按钮与键盘共用同一执行函数。
    val indentSelectionModel = remember { IndentSelectionModel() }
    var indentSelRequest by remember { mutableStateOf(0) }
    var indentSelOutdent by remember { mutableStateOf(false) }
    fun requestIndentSel(outdent: Boolean) { indentSelOutdent = outdent; indentSelRequest++ }
    // 块注释切换（v0.3.35）：纯 Kotlin BlockCommentModel 按文件类型推断 /* */ / <!-- --> / """ """，
    // 有选区包裹选区、无选区包裹当前行，再切换解除；结果经 vm.updateContent 回写，撤销树整步回退。
    val blockCommentModel = remember { BlockCommentModel() }
    var blockCommentRequest by remember { mutableStateOf(0) }
    // 文本工具（v0.3.36）：纯 Kotlin IndentationConvertModel / SortLinesModel 负责缩进风格转换与
    // 选区行排序；工具栏「⚒ 文本工具」展开快捷栏，点按经 request 递增 + kind 传入 CodeEditor，
    // 与快捷键共用同一执行函数；结果经 vm.updateContent 回写，撤销树整步回退。
    val indentationConvertModel = remember { IndentationConvertModel() }
    val sortLinesModel = remember { SortLinesModel() }
    var textToolOpen by remember { mutableStateOf(false) }
    var textToolRequest by remember { mutableStateOf(0) }
    var textToolKind by remember { mutableStateOf(TEXT_TO_SPACES) }
    fun requestTextTool(kind: Int) { textToolKind = kind; textToolRequest++ }
    fun openGoToLine() {
        gotoOpen = true
        gotoQuery = ""
        gotoNotice = ""
    }
    fun closeGoToLine() { gotoOpen = false }
    fun applyGoToLine() {
        if (activePath == null) { gotoNotice = "无活动文件"; return }
        val result = gotoModel.resolve(activeContent, gotoQuery)
        if (result == null) {
            gotoNotice = "输入无效，请用 行 或 行:列（如 42、42:8）"
            return
        }
        gotoOffset = result.offset
        gotoNotice = if (result.clamped)
            "已夹紧到第 ${result.line + 1} 行第 ${result.column + 1} 列"
        else "第 ${result.line + 1} 行第 ${result.column + 1} 列"
        gotoTick++
    }
    fun jumpToSymbol(line: Int) {
        outlineJumpLine = line
        outlineJumpTick++
    }
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
            ToolbarButton("// 注释", activePath != null) { commentRequest++ }
            ToolbarButton("/* 块注释", activePath != null) { blockCommentRequest++ }
            Spacer(Modifier.width(16.dp))
            ToolbarButton(
                text = if (outlineOpen) "☰ 大纲 ON" else "☰ 大纲",
                enabled = activePath != null
            ) {
                outlineOpen = !outlineOpen
            }
            Spacer(Modifier.width(16.dp))
            // 平板无物理键盘时也可点按打开 Ctrl+G 同款跳转行输入栏。
            ToolbarButton(
                text = if (gotoOpen) ":# 转到行 ON" else ":# 转到行",
                enabled = activePath != null
            ) {
                if (gotoOpen) closeGoToLine() else openGoToLine()
            }
            Spacer(Modifier.width(12.dp))
            // 行操作（v0.3.26）：平板无物理键盘时点按执行，与 Alt+↑/↓、Ctrl+D、Ctrl+Shift+K 同效。
            ToolbarButton("↑ 行", activePath != null) { requestLineOp(LineOperationsModel.MOVE_UP) }
            ToolbarButton("↓ 行", activePath != null) { requestLineOp(LineOperationsModel.MOVE_DOWN) }
            ToolbarButton("⧉ 复制", activePath != null) { requestLineOp(LineOperationsModel.DUPLICATE) }
            ToolbarButton("✕ 删行", activePath != null) { requestLineOp(LineOperationsModel.DELETE) }
            Spacer(Modifier.width(12.dp))
            // 多行选区缩进（v0.3.35）：平板无键盘时点按对选中行整体缩进/反缩进，与有选区 Tab/Shift+Tab 同效。
            ToolbarButton("⇥ 缩进", activePath != null) { requestIndentSel(false) }
            ToolbarButton("⇤ 反缩进", activePath != null) { requestIndentSel(true) }
            Spacer(Modifier.width(12.dp))
            // 按词移动/删除（v0.3.32）：平板无物理键盘时点按执行，与 Ctrl+←/→、Ctrl+Backspace/Delete 同效。
            ToolbarButton("← 词", activePath != null) { requestWord(WordMovementModel.MOVE_LEFT) }
            ToolbarButton("词 →", activePath != null) { requestWord(WordMovementModel.MOVE_RIGHT) }
            ToolbarButton("⌫ 词", activePath != null) { requestWord(WordMovementModel.DELETE_BEFORE) }
            ToolbarButton("⌦ 词", activePath != null) { requestWord(WordMovementModel.DELETE_AFTER) }
            Spacer(Modifier.width(12.dp))
            ToolbarButton(
                text = if (caseOpen) "Aa 大小写 ON" else "Aa 大小写",
                enabled = activePath != null
            ) {
                caseOpen = !caseOpen
            }
            Spacer(Modifier.width(12.dp))
            // 文本工具（v0.3.36）：展开缩进风格转换与选区行排序快捷栏，平板无键盘亦可点按。
            ToolbarButton(
                text = if (textToolOpen) "⚒ 文本工具 ON" else "⚒ 文本工具",
                enabled = activePath != null
            ) {
                textToolOpen = !textToolOpen
            }
            Spacer(Modifier.width(12.dp))
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
        // 跳转到指定行（v0.3.25）：Ctrl+G / 工具栏打开，输入 行[:列] 后 Enter 定位，Esc 关闭。
        if (gotoOpen) {
            GoToLineBar(
                query = gotoQuery,
                notice = gotoNotice,
                focusRequester = gotoFocus,
                onQueryChange = { gotoQuery = it; gotoNotice = "" },
                onApply = { applyGoToLine() },
                onClose = { closeGoToLine() }
            )
        }
        // 大小写转换快捷栏（v0.3.28）：点按对当前选区/光标词执行对应转换，无需物理键盘。
        if (caseOpen) {
            CaseBar(onTransform = { kind -> requestCase(kind) }, onClose = { caseOpen = false })
        }
        // 文本工具快捷栏（v0.3.36）：缩进风格互转与选区行升序/降序，无选区时作用于全文。
        if (textToolOpen) {
            TextToolBar(
                onApply = { kind -> requestTextTool(kind) },
                onClose = { textToolOpen = false }
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
                    onOpenGoToLine = { openGoToLine() },
                    onFindNext = { findNext() },
                    onFindPrev = { findPrev() },
                    onCloseFind = { closeFind() },
                    foldingModel = foldingModel,
                    foldTick = foldTick,
                    onFoldChanged = { foldTick++ },
                    commentModel = commentModel,
                    commentRequest = commentRequest,
                    autoIndentModel = autoIndentModel,
                    outlineJumpLine = outlineJumpLine,
                    outlineJumpTick = outlineJumpTick,
                    gotoOffset = gotoOffset,
                    gotoTick = gotoTick,
                    lineOpsModel = lineOpsModel,
                    lineOpRequest = lineOpRequest,
                    lineOpKind = lineOpKind,
                    caseModel = caseModel,
                    caseRequest = caseRequest,
                    caseKind = caseKind,
                    indentGuideModel = indentGuideModel,
                    wordModel = wordModel,
                    wordRequest = wordRequest,
                    wordKind = wordKind,
                    autoPairModel = autoPairModel,
                    indentSelectionModel = indentSelectionModel,
                    indentSelRequest = indentSelRequest,
                    indentSelOutdent = indentSelOutdent,
                    blockCommentModel = blockCommentModel,
                    blockCommentRequest = blockCommentRequest,
                    indentationConvertModel = indentationConvertModel,
                    sortLinesModel = sortLinesModel,
                    textToolRequest = textToolRequest,
                    textToolKind = textToolKind
                )
                CompletionPopup(vm)
                // 代码大纲面板（v0.3.23）：右侧固定宽度列，列出当前文件声明符号；
                // 点击某符号复用光标行→像素滚动锚点，平滑定位到目标行，保持编辑语义不变。
                if (outlineOpen) {
                    OutlinePanel(
                        symbols = outlineSymbols,
                        onJump = { line -> jumpToSymbol(line) },
                        onClose = { outlineOpen = false }
                    )
                }
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
    onOpenGoToLine: () -> Unit,
    onFindNext: () -> Unit,
    onFindPrev: () -> Unit,
    onCloseFind: () -> Unit,
    foldingModel: FoldingModel,
    foldTick: Int,
    onFoldChanged: () -> Unit,
    commentModel: CommentModel,
    commentRequest: Int,
    autoIndentModel: AutoIndentModel,
    outlineJumpLine: Int,
    outlineJumpTick: Int,
    gotoOffset: Int,
    gotoTick: Int,
    lineOpsModel: LineOperationsModel,
    lineOpRequest: Int,
    lineOpKind: Int,
    caseModel: CaseModel,
    caseRequest: Int,
    caseKind: Int,
    indentGuideModel: IndentGuideModel,
    wordModel: WordMovementModel,
    wordRequest: Int,
    wordKind: Int,
    autoPairModel: AutoPairModel,
    indentSelectionModel: IndentSelectionModel,
    indentSelRequest: Int,
    indentSelOutdent: Boolean,
    blockCommentModel: BlockCommentModel,
    blockCommentRequest: Int,
    indentationConvertModel: IndentationConvertModel,
    sortLinesModel: SortLinesModel,
    textToolRequest: Int,
    textToolKind: Int
) {
    // 滚动状态提升到 CodeEditor：BasicTextField 走系统自带滚动，外层 Box 的 verticalScroll
    // 仅作为「按行跳转」的可控代理（把滚动锚点换算为像素后 animateScrollTo）。
    // 说明：BasicTextField 内部自行滚动且旧的 value/onValueChange 重载不暴露 scrollState，
    // 故跳转通过外层可滚动容器 + 行高像素换算实现，与键盘输入/撤销重做互不影响。
    val scrollState = rememberScrollState()
    // 光标位置（v0.3.20）：BasicTextField 改用 TextFieldValue 重载以取得 selection.end，
    // 供括号匹配内核定位。外部内容变化（撤销/重做/查找替换）在 LaunchedEffect 中同步回写，
    // 夹紧选区，保持原有编辑语义不变。
    var fieldValue by remember(tab.path) { mutableStateOf(TextFieldValue(tab.content)) }
    LaunchedEffect(tab.content) {
        if (fieldValue.text != tab.content) {
            val max = tab.content.length
            val s = fieldValue.selection.start.coerceAtMost(max)
            val e = fieldValue.selection.end.coerceAtMost(max)
            fieldValue = TextFieldValue(tab.content, TextRange(s, e))
        }
    }
    val caretOffset = fieldValue.selection.end.coerceAtMost(tab.content.length)
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

    // 括号匹配（v0.3.20）：纯 Kotlin BracketMatchModel 在代码区跳过字符串/注释后按栈配对，
    // 取光标左右相邻的括号，得到两个单字符区间，复用 SelectionHighlightModel 画高亮矩形。
    // 仅视觉指示，不拦截输入；失配或字符串/注释中的符号不参与，失败安全。
    val bracketModel = remember { BracketMatchModel() }
    val bracketMatch = remember(tab.content, caretOffset) {
        bracketModel.matchAt(tab.content, caretOffset)
    }
    val bracketRects = remember(tab.content, bracketMatch, charWidthPx, lineHeightPx) {
        val bm = bracketMatch
        if (bm == null) emptyList()
        else selectionModel.highlightOf(
            tab.content, listOf(bm.openRange, bm.closeRange), lineHeightPx, charWidthPx
        )
    }
    fun jumpToMatchingBracket() {
        val target = bracketModel.jumpToMatch(tab.content, caretOffset)
        if (target != caretOffset) fieldValue = fieldValue.copy(selection = TextRange(target))
    }

    // 缩进指南线（v0.3.30）：纯 Kotlin IndentGuideModel 算出的连续竖线段，坐标口径与 caret /
    // 选区高亮完全一致（textStartPx/textTopPx + 列宽/行高），随文本与 tab 宽度重组，不拦截输入。
    val guideRuns = remember(tab.content) { indentGuideModel.runsOf(tab.content) }

    // 代码折叠（v0.3.21）：纯 Kotlin FoldingModel 识别可折叠区间（花括号配对 + 缩进块）。
    // 折叠区间随文本变化重算；折叠态由内核按 path+起始行记快照，foldTick 变化触发行号栏重组。
    // 折叠/展开结果经 vm.updateContent 回写，撤销树可整步回退，补全/minimap/查找自动刷新。
    val foldRegions = remember(tab.content, foldTick) { foldingModel.regionsOf(tab.content) }
    fun foldAt(line: Int) {
        val region = foldingModel.regionAt(foldRegions, line) ?: return
        val next = foldingModel.fold(tab.path, tab.content, region)
        if (next != tab.content) { vm.updateContent(tab.path, next); onFoldChanged() }
    }
    fun unfoldAt(line: Int) {
        val next = foldingModel.unfold(tab.path, tab.content, line)
        if (next != tab.content) { vm.updateContent(tab.path, next); onFoldChanged() }
    }

    // 行注释切换（v0.3.22）：取当前选区（或光标）覆盖的首末行，按文件类型推断注释符号，
    // 纯 Kotlin CommentModel 统一加/去注释；结果经 vm.updateContent 回写，撤销树整步回退，
    // 新光标偏移随本行增删同步。工具栏按钮（commentRequest 递增）与 Ctrl+/ 共用此函数。
    fun toggleComment() {
        val text = tab.content
        if (text.isEmpty()) return
        val selStart = fieldValue.selection.start.coerceAtMost(text.length)
        val selEnd = fieldValue.selection.end.coerceAtMost(text.length)
        val startLine = commentModel.lineOfOffset(text, selStart)
        val endLine = commentModel.lineOfOffset(text, selEnd)
        val token = commentModel.tokenFor(tab.path)
        val result = commentModel.toggle(text, startLine, endLine, token, selEnd)
        if (result.text == text) return
        vm.updateContent(tab.path, result.text)
        fieldValue = fieldValue.copy(selection = TextRange(result.caret))
    }
    // 工具栏触发：仅在计数 > 0（即真实点击）时执行，跳过初始组合。
    LaunchedEffect(commentRequest) {
        if (commentRequest > 0) toggleComment()
    }

    // 块注释切换（v0.3.35）：取当前选区（或折叠光标所在行），按文件类型推断符号对，
    // 纯 Kotlin BlockCommentModel 包裹/解除；结果经 vm.updateContent 回写，撤销树整步回退。
    // 工具栏按钮（blockCommentRequest 递增）与 Ctrl+Shift+A 共用此函数。
    fun toggleBlockComment() {
        val text = tab.content
        if (text.isEmpty()) return
        val tokens = blockCommentModel.tokensFor(tab.path) ?: return
        val selStart = fieldValue.selection.start.coerceAtMost(text.length)
        val selEnd = fieldValue.selection.end.coerceAtMost(text.length)
        val result = blockCommentModel.toggle(text, selStart, selEnd, tokens)
        if (!result.changed || result.text == text) return
        vm.updateContent(tab.path, result.text)
        fieldValue = fieldValue.copy(
            selection = TextRange(result.selectionStart, result.selectionEnd)
        )
    }
    LaunchedEffect(blockCommentRequest) {
        if (blockCommentRequest > 0) toggleBlockComment()
    }

    // 多行选区整体缩进/反缩进（v0.3.35）：仅处理有选区的场景（无选区交还单行自动缩进逻辑）；
    // 内核从文档推断 tab/空格风格，结果经 vm.updateContent 回写，选区随增删同步、撤销树整步回退。
    // 工具栏按钮（indentSelRequest 递增）与有选区的 Tab/Shift+Tab 共用此函数。
    fun applyIndentSelection(outdent: Boolean) {
        val text = tab.content
        if (text.isEmpty()) return
        val selStart = fieldValue.selection.start.coerceAtMost(text.length)
        val selEnd = fieldValue.selection.end.coerceAtMost(text.length)
        if (selStart == selEnd) return
        val result = indentSelectionModel.apply(
            text, selStart, selEnd, indentSelectionModel.optionsFor(text), outdent
        )
        if (result.text == text) return
        vm.updateContent(tab.path, result.text)
        fieldValue = fieldValue.copy(
            selection = TextRange(result.selectionStart, result.selectionEnd)
        )
    }
    LaunchedEffect(indentSelRequest) {
        if (indentSelRequest > 0) applyIndentSelection(indentSelOutdent)
    }

    // 文本工具（v0.3.36）：缩进风格转换与选区行排序，结果经 vm.updateContent 回写、撤销树整步回退。
    // 无选区时两内核均作用于全文；有选区时仅处理覆盖行，选区偏移由内核按行增量同步。
    fun applyTextTool(kind: Int) {
        val text = tab.content
        if (text.isEmpty()) return
        val selStart = fieldValue.selection.start.coerceAtMost(text.length)
        val selEnd = fieldValue.selection.end.coerceAtMost(text.length)
        val width = indentSelectionModel.optionsFor(text).safeTabWidth()
        val result = when (kind) {
            TEXT_TO_SPACES -> indentationConvertModel.toSpaces(text, width, selStart, selEnd)
            TEXT_TO_TABS -> indentationConvertModel.toTabs(text, width, selStart, selEnd)
            TEXT_SORT_ASC -> sortLinesModel.ascending(text, selStart, selEnd)
            TEXT_SORT_DESC -> sortLinesModel.descending(text, selStart, selEnd)
            else -> null
        } ?: return
        if (result.text == text) return
        vm.updateContent(tab.path, result.text)
        fieldValue = fieldValue.copy(
            selection = TextRange(result.selectionStart, result.selectionEnd)
        )
    }
    LaunchedEffect(textToolRequest) {
        if (textToolRequest > 0) applyTextTool(textToolKind)
    }

    // 行操作（v0.3.26）：取当前选区（或光标）覆盖的首末行，调用纯 Kotlin 内核移动/复制/删除，
    // 结果经 vm.updateContent 回写——撤销树可整步回退，补全/minimap/查找高亮自动刷新。
    // 工具栏按钮（request 递增）与 Alt+↑/↓、Ctrl+D、Ctrl+Shift+K 共用此函数。
    fun applyLineOp(kind: Int) {
        val text = tab.content
        if (text.isEmpty()) return
        val selStart = fieldValue.selection.start.coerceAtMost(text.length)
        val selEnd = fieldValue.selection.end.coerceAtMost(text.length)
        val startLine = lineOpsModel.lineOfOffset(text, selStart)
        val endLine = lineOpsModel.lineOfOffset(text, selEnd)
        val result = when (kind) {
            LineOperationsModel.MOVE_UP -> lineOpsModel.moveUp(text, startLine, endLine, selEnd)
            LineOperationsModel.MOVE_DOWN -> lineOpsModel.moveDown(text, startLine, endLine, selEnd)
            LineOperationsModel.DUPLICATE -> lineOpsModel.duplicate(text, startLine, endLine, selEnd)
            LineOperationsModel.DELETE -> lineOpsModel.delete(text, startLine, endLine, selEnd)
            else -> null
        } ?: return
        if (result.text == text) return
        vm.updateContent(tab.path, result.text)
        fieldValue = fieldValue.copy(
            selection = TextRange(result.caret.coerceAtMost(result.text.length))
        )
    }
    LaunchedEffect(lineOpRequest) {
        if (lineOpRequest > 0) applyLineOp(lineOpKind)
    }

    // 大小写转换（v0.3.28）：有选区则整段转换，无选区则由内核取光标所在词。
    // 工具栏快捷栏与 Ctrl+Shift+U/L 共用此函数；新文本经 vm.updateContent 回写，
    // 撤销树可整步回退，转换后的片段保持选中便于连续切换风格。
    fun applyCase(kind: Int) {
        val text = tab.content
        if (text.isEmpty()) return
        val selStart = fieldValue.selection.start.coerceAtMost(text.length)
        val selEnd = fieldValue.selection.end.coerceAtMost(text.length)
        val result = caseModel.apply(text, selStart, selEnd, kind) ?: return
        if (result.text == text) return
        vm.updateContent(tab.path, result.text)
        fieldValue = fieldValue.copy(selection = TextRange(result.start, result.end))
    }
    LaunchedEffect(caseRequest) {
        if (caseRequest > 0) applyCase(caseKind)
    }

    // 按词移动/删除（v0.3.32）：移动仅改 selection 不碰文本；删除经 vm.updateContent 回写，
    // 撤销树整步回退。工具栏与 Ctrl+←/→、Ctrl+Backspace/Delete 共用此函数。
    fun applyWord(kind: Int) {
        val text = tab.content
        if (text.isEmpty()) return
        val selStart = fieldValue.selection.start.coerceAtMost(text.length)
        val selEnd = fieldValue.selection.end.coerceAtMost(text.length)
        when (kind) {
            WordMovementModel.MOVE_LEFT ->
                fieldValue = fieldValue.copy(selection = TextRange(wordModel.wordLeft(text, selStart)))
            WordMovementModel.MOVE_RIGHT ->
                fieldValue = fieldValue.copy(selection = TextRange(wordModel.wordRight(text, selEnd)))
            WordMovementModel.DELETE_BEFORE -> {
                val result = wordModel.deleteWordBefore(text, selStart, selEnd) ?: return
                if (result.text == text) return
                vm.updateContent(tab.path, result.text)
                fieldValue = fieldValue.copy(selection = TextRange(result.caret))
            }
            WordMovementModel.DELETE_AFTER -> {
                val result = wordModel.deleteWordAfter(text, selStart, selEnd) ?: return
                if (result.text == text) return
                vm.updateContent(tab.path, result.text)
                fieldValue = fieldValue.copy(selection = TextRange(result.caret))
            }
        }
    }
    LaunchedEffect(wordRequest) {
        if (wordRequest > 0) applyWord(wordKind)
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

    // 代码大纲跳转（v0.3.23）：点击符号经 outlineJumpLine/Tick 传入目标行，平滑滚动到该行居中。
    // 与查找跳转同口径（行号→像素→animateScrollTo），并把光标移到目标行首，保持编辑语义不变。
    LaunchedEffect(outlineJumpTick) {
        if (outlineJumpTick > 0 && outlineJumpLine >= 0 && lineHeightPx > 0f) {
            val safeLine = outlineJumpLine.coerceAtMost(sourceLineCount - 1)
            scrollState.animateScrollTo((safeLine * lineHeightPx).toInt().coerceAtLeast(0))
            // 光标落在目标行首，便于紧接着键盘编辑；偏移夹紧避免越界。
            var off = 0
            var n = 0
            val text = tab.content
            while (n < safeLine && off < text.length) {
                if (text[off] == '\n') n++
                off++
            }
            fieldValue = fieldValue.copy(selection = TextRange(off.coerceAtMost(text.length)))
        }
    }

    // 跳转到指定行（v0.3.25）：内核已给出夹紧后的光标偏移，这里复用 CursorLayoutModel 反算行号，
    // 走与查找/大纲完全相同的「行号→像素→animateScrollTo」锚点；光标经 TextFieldValue.selection 移动，
    // 不改动文本内容，因此撤销树、补全、minimap 与全部高亮均不受影响。
    LaunchedEffect(gotoTick) {
        if (gotoTick > 0 && gotoOffset >= 0 && lineHeightPx > 0f) {
            val text = tab.content
            val off = gotoOffset.coerceAtMost(text.length)
            val line = caretLayout.lineColumnOf(text, off).line
            scrollState.animateScrollTo((line * lineHeightPx).toInt().coerceAtLeast(0))
            fieldValue = fieldValue.copy(selection = TextRange(off))
        }
    }

    Row(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxSize().weight(1f)
                .onSizeChanged { viewportPx = it.height.toFloat() }
                .verticalScroll(scrollState)
        ) {
            BasicTextField(
                value = fieldValue,
                onValueChange = { next ->
                    // 自动配对（v0.3.34）：比较上一次值与本次输入推断编辑类型。
                    // 内核不接管时保留 BasicTextField 默认行为（IME/粘贴/删除/选区拖拽等）。
                    val paired = autoPairModel.afterChange(
                        fieldValue.text, fieldValue.selection.start, fieldValue.selection.end,
                        next.text, next.selection.start, next.selection.end
                    )
                    if (paired != null) {
                        fieldValue = TextFieldValue(
                            paired.text, TextRange(paired.selectionStart, paired.selectionEnd)
                        )
                        vm.updateContent(tab.path, paired.text)
                    } else {
                        fieldValue = next
                        vm.updateContent(tab.path, next.text)
                    }
                },
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
                            e.isCtrlPressed && e.key == Key.G -> { onOpenGoToLine(); true }
                            e.isCtrlPressed && e.key == Key.Slash -> { toggleComment(); true }
                            // 块注释切换（v0.3.35）：Ctrl+Shift+A 包裹/解除选区或当前行块注释。
                            e.isCtrlPressed && e.isShiftPressed && e.key == Key.A -> {
                                toggleBlockComment(); true
                            }
                            // 大小写转换（v0.3.28）：Ctrl+Shift+U 大写、Ctrl+Shift+L 小写；
                            // 其余风格走工具栏「Aa 大小写」快捷栏，避免占用常用编辑组合键。
                            e.isCtrlPressed && e.isShiftPressed && e.key == Key.U -> {
                                applyCase(CaseModel.UPPER); true
                            }
                            e.isCtrlPressed && e.isShiftPressed && e.key == Key.L -> {
                                applyCase(CaseModel.LOWER); true
                            }
                            // 行操作（v0.3.26）：Alt+↑/↓ 上下移动整行，Ctrl+D 复制行，Ctrl+Shift+K 删除行。
                            e.isAltPressed &&
                                (e.key == Key.DirectionUp || e.key == Key.DirectionDown) -> {
                                applyLineOp(
                                    if (e.key == Key.DirectionUp) LineOperationsModel.MOVE_UP
                                    else LineOperationsModel.MOVE_DOWN
                                )
                                true
                            }
                            // 按词移动/删除（v0.3.32）：Ctrl+←/→ 移动光标一个词块，
                            // Ctrl+Backspace/Ctrl+Delete 删除光标左/右侧词块（有选区则删选区）。
                            e.isCtrlPressed && !e.isShiftPressed && e.key == Key.DirectionLeft -> {
                                applyWord(WordMovementModel.MOVE_LEFT); true
                            }
                            e.isCtrlPressed && !e.isShiftPressed && e.key == Key.DirectionRight -> {
                                applyWord(WordMovementModel.MOVE_RIGHT); true
                            }
                            e.isCtrlPressed && e.key == Key.Backspace -> {
                                applyWord(WordMovementModel.DELETE_BEFORE); true
                            }
                            e.isCtrlPressed && e.key == Key.Delete -> {
                                applyWord(WordMovementModel.DELETE_AFTER); true
                            }
                            // 自动配对（v0.3.34）：光标处于成对符号中间时普通 Backspace 一次删两个，
                            // 有选区/不成对/越界则交还 BasicTextField 默认退格（删单个字符或选区）。
                            e.key == Key.Backspace && !e.isCtrlPressed -> {
                                val text = tab.content
                                val s = fieldValue.selection.start.coerceAtMost(text.length)
                                val en = fieldValue.selection.end.coerceAtMost(text.length)
                                val result = autoPairModel.onBackspace(text, s, en)
                                if (result != null) {
                                    fieldValue = TextFieldValue(result.text, TextRange(result.selectionStart))
                                    vm.updateContent(tab.path, result.text)
                                    true
                                } else false
                            }
                            e.isCtrlPressed && !e.isShiftPressed && e.key == Key.D -> {
                                applyLineOp(LineOperationsModel.DUPLICATE); true
                            }
                            e.isCtrlPressed && e.isShiftPressed && e.key == Key.K -> {
                                applyLineOp(LineOperationsModel.DELETE); true
                            }
                            // 自动缩进（v0.3.24）：回车延续/增加一级缩进，Tab/Shift+Tab 缩进与反缩进。
                            // 仅在内核确实改变文本时消费按键，其它场景交还 BasicTextField 默认行为。
                            e.key == Key.Enter && !e.isCtrlPressed && !e.isShiftPressed -> {
                                val result = autoIndentModel.onEnter(tab.content, caretOffset)
                                if (result.text != tab.content) {
                                    fieldValue = TextFieldValue(result.text, TextRange(result.caret))
                                    vm.updateContent(tab.path, result.text)
                                    true
                                } else false
                            }
                            // 多行选区缩进（v0.3.35）：有选区时 Tab/Shift+Tab 走块缩进内核；
                            // 无选区时保留 AutoIndentModel 单行 Tab/Shift+Tab 的原行为。
                            e.key == Key.Tab && !e.isCtrlPressed &&
                                fieldValue.selection.start != fieldValue.selection.end -> {
                                applyIndentSelection(e.isShiftPressed)
                                true
                            }
                            e.key == Key.Tab && !e.isCtrlPressed &&
                                fieldValue.selection.start == fieldValue.selection.end -> {
                                val result = autoIndentModel.onTab(
                                    tab.content, caretOffset, outdent = e.isShiftPressed
                                )
                                if (result.text != tab.content) {
                                    fieldValue = TextFieldValue(result.text, TextRange(result.caret))
                                    vm.updateContent(tab.path, result.text)
                                    true
                                } else false
                            }
                            e.key == Key.F3 -> { if (e.isShiftPressed) onFindPrev() else onFindNext(); true }
                            e.isCtrlPressed && e.isShiftPressed && e.key == Key.Backslash -> {
                                jumpToMatchingBracket(); true
                            }
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
                        // 行号栏 + 折叠标记（v0.3.21）：每行左侧固定宽度区域。
                        // 折叠态优先显示 ▸（点击展开），否则可折叠区间首行显示 ▾（点击折叠）；
                        // 标记用 clickable，仅占行号栏空白区，不影响 BasicTextField 输入焦点。
                        // foldTick 参与重组键，保证折叠后行数/状态变化立即刷新。
                        Column {
                            for (i in 1..sourceLineCount.coerceAtMost(60)) {
                                val zeroLine = i - 1
                                Row(
                                    modifier = Modifier.height(VscodeTheme.LINE_HEIGHT.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val foldedHere = foldingModel.isFolded(tab.path, zeroLine)
                                    val foldableHere = !foldedHere && foldingModel.regionAt(foldRegions, zeroLine) != null
                                    Box(
                                        modifier = Modifier.width(14.dp).height(VscodeTheme.LINE_HEIGHT.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        if (foldedHere) {
                                            Text("▸", color = VscodeTheme.Accent, fontSize = 11.sp,
                                                modifier = Modifier.clickable { unfoldAt(zeroLine) })
                                        } else if (foldableHere) {
                                            Text("▾", color = VscodeTheme.FgDim, fontSize = 11.sp,
                                                modifier = Modifier.clickable { foldAt(zeroLine) })
                                        }
                                    }
                                    Text(
                                        i.toString().padStart(2),
                                        color = VscodeTheme.FgDim,
                                        fontSize = VscodeTheme.FONT_SIZE.sp,
                                        fontFamily = FontFamily.Monospace,
                                        lineHeight = VscodeTheme.LINE_HEIGHT.sp
                                    )
                                }
                            }
                        }
                        Spacer(Modifier.width(40.dp))
                        inner()
                        // 缩进指南线层（v0.3.30）：每个连续段画一条 1dp 竖线，自列 0 起按列宽定位。
                        // 与 caret/选区同用 textStartPx/textTopPx + charWidthPx/lineHeightPx 口径；
                        // 无 clickable/pointerInput，不拦截键盘与触摸输入，纯视觉指示。
                        guideRuns.forEach { run ->
                            Box(
                                modifier = Modifier
                                    .offset(
                                        x = with(density) { (textStartPx + run.column * charWidthPx).toDp() },
                                        y = with(density) { (textTopPx + run.startLine * lineHeightPx).toDp() }
                                    )
                                    .width(1.dp)
                                    .height(with(density) { ((run.endLine - run.startLine + 1) * lineHeightPx).toDp() })
                                    .background(VscodeTheme.FgDim.copy(alpha = 0.30f))
                            )
                        }
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
                        // 括号匹配高亮层（v0.3.20）：光标左右相邻括号与其配对项各画一个亮黄块。
                        // 与查找高亮同口径（SelectionHighlightModel 拆矩形），无 clickable/pointerInput。
                        bracketRects.forEach { r ->
                            Box(
                                modifier = Modifier
                                    .offset(
                                        x = with(density) { (textStartPx + r.xPx).toDp() },
                                        y = with(density) { (textTopPx + r.yPx).toDp() }
                                    )
                                    .width(with(density) { r.widthPx.toDp() })
                                    .height(VscodeTheme.LINE_HEIGHT.dp)
                                    .background(VscodeTheme.Yellow.copy(alpha = 0.65f))
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

/** 跳转到指定行输入栏（v0.3.25）：Ctrl+G / 工具栏「:# 转到行」打开，Enter 定位、Esc 关闭。 */
@Composable
private fun GoToLineBar(
    query: String,
    notice: String,
    focusRequester: FocusRequester,
    onQueryChange: (String) -> Unit,
    onApply: () -> Unit,
    onClose: () -> Unit
) {
    LaunchedEffect(Unit) { focusRequester.requestFocus() }
    Row(
        modifier = Modifier.fillMaxWidth()
            .background(VscodeTheme.WidgetBg).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(":#", color = VscodeTheme.FgMuted, fontSize = 12.sp,
            fontFamily = FontFamily.Monospace)
        Spacer(Modifier.width(8.dp))
        BasicTextField(
            value = query,
            onValueChange = onQueryChange,
            modifier = Modifier.weight(1f).background(VscodeTheme.InputBg).padding(6.dp)
                .focusRequester(focusRequester)
                .onKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when {
                        e.key == Key.Enter -> { onApply(); true }
                        e.key == Key.Escape -> { onClose(); true }
                        else -> false
                    }
                },
            singleLine = true,
            textStyle = TextStyle(color = VscodeTheme.Fg, fontSize = 12.sp,
                fontFamily = FontFamily.Monospace),
            decorationBox = { inner ->
                if (query.isEmpty()) Text("行[:列]，如 42 或 42:8",
                    color = VscodeTheme.FgDim, fontSize = 12.sp)
                inner()
            }
        )
        Spacer(Modifier.width(8.dp))
        if (notice.isNotEmpty()) {
            Text(notice, color = VscodeTheme.FgDim, fontSize = 11.sp, maxLines = 1)
            Spacer(Modifier.width(8.dp))
        }
        ToolbarButton("转到", enabled = true, onClick = onApply)
        Spacer(Modifier.width(4.dp))
        ToolbarButton("关闭 Esc", enabled = true, onClick = onClose)
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

/**
 * 代码大纲面板（v0.3.23）：右侧固定宽度列，列出 [symbols] 的「图标 + 名称 + 行号」。
 * 符号种类用不同颜色图标区分（类/接口/枚举/结构体/函数/方法/变量/命名空间），
 * 点击某行经 [onJump] 把目标行交给 CodeEditor 的滚动锚点定位；纯 UI，不改编辑语义。
 */
@Composable
private fun OutlinePanel(
    symbols: List<SymbolOutlineModel.Symbol>,
    onJump: (Int) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxHeight().width(160.dp)
            .background(VscodeTheme.SideBarBg)
            .padding(start = 6.dp, end = 4.dp, top = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("☰ 大纲 (${symbols.size})", color = VscodeTheme.FgMuted, fontSize = 11.sp,
                modifier = Modifier.weight(1f))
            Text("✕", color = VscodeTheme.FgDim, fontSize = 12.sp,
                modifier = Modifier.clickable { onClose() })
        }
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            if (symbols.isEmpty()) {
                Text("无符号", color = VscodeTheme.FgDim, fontSize = 11.sp,
                    modifier = Modifier.padding(4.dp))
            } else {
                symbols.forEach { sym ->
                    val (icon, tint) = when (sym.kind) {
                        SymbolOutlineModel.Kind.CLASS -> "C" to VscodeTheme.Blue
                        SymbolOutlineModel.Kind.INTERFACE -> "I" to VscodeTheme.Blue
                        SymbolOutlineModel.Kind.OBJECT -> "O" to VscodeTheme.Green
                        SymbolOutlineModel.Kind.ENUM -> "E" to VscodeTheme.Yellow
                        SymbolOutlineModel.Kind.STRUCT -> "S" to VscodeTheme.Blue
                        SymbolOutlineModel.Kind.FUNCTION -> "ƒ" to VscodeTheme.Purple
                        SymbolOutlineModel.Kind.METHOD -> "m" to VscodeTheme.Purple
                        SymbolOutlineModel.Kind.VARIABLE -> "v" to VscodeTheme.Orange
                        SymbolOutlineModel.Kind.NAMESPACE -> "N" to VscodeTheme.FgMuted
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .padding(start = (sym.indent.coerceAtMost(8) * 2).dp, top = 2.dp, bottom = 2.dp)
                            .clickable { onJump(sym.line) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(icon, color = tint, fontSize = 11.sp)
                        Spacer(Modifier.width(6.dp))
                        Text(sym.name, color = VscodeTheme.Fg, fontSize = 12.sp,
                            maxLines = 1, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(4.dp))
                        Text("${sym.line + 1}", color = VscodeTheme.FgDim, fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

/** 大小写转换快捷栏（v0.3.28）：平板无键盘时点按执行 7 种转换，均作用于当前选区或光标词。 */
@Composable
private fun CaseBar(onTransform: (Int) -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .background(VscodeTheme.TabInactiveBg).padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("大小写:", color = VscodeTheme.FgDim, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        ToolbarButton("ABC 大写") { onTransform(CaseModel.UPPER) }
        ToolbarButton("abc 小写") { onTransform(CaseModel.LOWER) }
        ToolbarButton("Aa 首字母") { onTransform(CaseModel.CAPITALIZE) }
        ToolbarButton("Aa 标题") { onTransform(CaseModel.TITLE) }
        ToolbarButton("camel 驼峰") { onTransform(CaseModel.CAMEL) }
        ToolbarButton("snake 蛇形") { onTransform(CaseModel.SNAKE) }
        ToolbarButton("kebab 短横线") { onTransform(CaseModel.KEBAB) }
        Spacer(Modifier.width(8.dp))
        ToolbarButton("关闭") { onClose() }
    }
}

/** 文本工具快捷栏（v0.3.36）：缩进风格互转与选区行排序，复用 [CaseBar] 的横向滚动布局。 */
@Composable
private fun TextToolBar(onApply: (Int) -> Unit, onClose: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            .background(VscodeTheme.TabInactiveBg).padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("文本工具:", color = VscodeTheme.FgDim, fontSize = 12.sp)
        Spacer(Modifier.width(8.dp))
        ToolbarButton("⇥→空格") { onApply(TEXT_TO_SPACES) }
        ToolbarButton("空格→⇥") { onApply(TEXT_TO_TABS) }
        ToolbarButton("A→Z 升序") { onApply(TEXT_SORT_ASC) }
        ToolbarButton("Z→A 降序") { onApply(TEXT_SORT_DESC) }
        Spacer(Modifier.width(8.dp))
        ToolbarButton("关闭") { onClose() }
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
