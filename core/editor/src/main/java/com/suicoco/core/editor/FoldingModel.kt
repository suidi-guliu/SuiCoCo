package com.suicoco.core.editor

import kotlin.math.max
import kotlin.math.min

/**
 * 代码折叠内核（纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测）。
 *
 * 背景：[EditorArea] 的 BasicTextField 是单一文本域，无法做视图级行折叠。本内核把 VSCode
 * 最基础的「代码折叠 / 展开」拆成可单测的纯逻辑：① 扫描文档识别可折叠区间（花括号配对为主，
 * 缩进块如 Python 的 `:` 为辅），跳过字符串/字符字面量与 `//`、`/* */` 注释；② 折叠时把区间
 * 的中间行替换为一行占位符（`⋯ (N lines)`），返回新文本交给 UI 经 `MainViewModel.updateContent`
 * 写回，因此撤销树快照、补全刷新、minimap 重算自动同步；③ 展开时按「文件 + 起始行」的内存快照
 * 还原原文，还原前比对当前文本确为折叠态，否则失败安全放弃，避免误覆盖用户后续编辑。
 *
 * 口径与约束：
 *  - 行号、偏移均为 0-based；区间 [startLine, endLine] 含两端；
 *  - 括号驱动识别 `{}`（Kotlin/Java/C/C++/JS 通用），缩进驱动识别行尾 `:` 的更深缩进块（Python）；
 *  - 快照按 [path] 隔离，同一路径同一区间的折叠幂等（重复 fold 不再二次折叠）；
 *  - 容量有上限 [Config.maxRegions]，超大文件只取前 N 个区间，避免长文档卡顿；
 *  - 全部公开方法失败安全：空文本 / 越界行 / 非法配置均安全回退，绝不抛异常；纯函数幂等；
 *  - 保持三条不变量（无 host root / 官方源 / 仅 arm64）：本类不涉及任何系统能力。
 */
class FoldingModel(private val config: Config = Config()) {

    /** 可调配置。非法阈值回退默认，避免外部传入 0 / 负数导致异常行为。 */
    data class Config(
        val maxRegions: Int = DEFAULT_MAX_REGIONS,
        val minFoldLines: Int = DEFAULT_MIN_FOLD_LINES,
        val tabWidth: Int = DEFAULT_TAB_WIDTH,
        val placeholder: String = DEFAULT_PLACEHOLDER
    )

    /** 一个可折叠区间，行号 0-based 且含两端。 */
    data class Region(val startLine: Int, val endLine: Int, val indent: Int) {
        /** 区间内「可被折叠掉」的行数（不含首行）。 */
        val hiddenLines: Int get() = endLine - startLine
        /** 区间覆盖的总行数（含首尾）。 */
        val lineCount: Int get() = endLine - startLine + 1
    }

    /** 折叠快照：记录折叠后文本、原文与起始行，供展开安全还原。 */
    private data class Snapshot(val foldedText: String, val originalText: String, val startLine: Int)

    private enum class ScanState { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR_LITERAL }

    /** path → (startLine → 快照)，按文件隔离，切换/重开文件互不干扰。 */
    private val snapshots = HashMap<String, HashMap<Int, Snapshot>>()

    /**
     * 扫描文档识别所有可折叠区间，结果按 [Region.startLine] 升序、无重复起始行。
     * 花括号配对优先；同一 startLine 若被多种策略识别，保留覆盖更长的区间。
     */
    fun regionsOf(content: String): List<Region> {
        if (content.isEmpty()) return emptyList()
        val lines = splitLines(content)
        if (lines.size < 2) return emptyList()
        val limit = if (config.maxRegions < 1) DEFAULT_MAX_REGIONS else config.maxRegions
        val minLines = if (config.minFoldLines < 1) DEFAULT_MIN_FOLD_LINES else config.minFoldLines
        val tw = if (config.tabWidth < 1) DEFAULT_TAB_WIDTH else config.tabWidth
        // startLine → region，保留覆盖最长的那个（用 MutableMap 去重）
        val byStart = LinkedHashMap<Int, Region>()

        scanBraces(lines, minLines, tw, byStart)
        scanIndentBlocks(lines, minLines, tw, byStart)

        return byStart.values.sortedBy { it.startLine }.take(limit)
    }

    /** 取以 [line] 行起始的可折叠区间（供行号栏折叠标记判定）；无则 null。 */
    fun regionAt(regions: List<Region>, line: Int): Region? =
        regions.firstOrNull { it.startLine == line }

    /**
     * 折叠 [region]：把区间中间行替换为一行占位符，返回新文本；同时按 [path] 存快照。
     * 失败安全：文本为空 / 区间越界 / 区间不含可折叠行时原样返回；已是折叠态则幂等返回原文。
     * 调用方负责把返回文本经 `vm.updateContent` 写回，撤销树即可整步回退。
     */
    fun fold(path: String, content: String, region: Region): String {
        if (content.isEmpty()) return content
        val lines = splitLines(content)
        val s = region.startLine
        val e = region.endLine
        if (s < 0 || e >= lines.size || e <= s) return content
        // 已折叠且当前文本正是快照结果：幂等，不再二次折叠
        val existing = snapshots[path]?.get(s)
        if (existing != null && existing.foldedText == content) return content

        // 折叠区间 [s+1, e] 全部替换为一行占位符，保留首行 s。花括号的闭合行 `}` 也折入，
        // 缩进块的块尾行同样折入，两种区间语义一致。
        val hidden = e - s
        val placeholderLine = " ".repeat(region.indent.coerceIn(0, 64)) +
            placeholderText() + " ($hidden lines)"
        val rebuilt = ArrayList<String>(lines.size - hidden + 1)
        for (i in lines.indices) {
            when {
                i <= s -> rebuilt.add(lines[i])
                i == s + 1 -> rebuilt.add(placeholderLine)
                i > e -> rebuilt.add(lines[i])
                else -> Unit // s+2..e 的行被折叠掉
            }
        }
        val folded = joinLines(rebuilt, content)
        snapshots.getOrPut(path) { HashMap() }[s] = Snapshot(folded, content, s)
        return folded
    }

    /**
     * 展开 [path] 起始于 [line] 的折叠区间：仅当当前文本与快照的折叠态完全一致时还原原文，
     * 否则（用户已在折叠后编辑、或快照不存在）原样返回，绝不覆盖用户内容。展开后移除快照。
     */
    fun unfold(path: String, content: String, line: Int): String {
        val snap = snapshots[path]?.get(line) ?: return content
        if (content != snap.foldedText) {
            // 文本已被改动，快照失效：清理并返回原文，失败安全
            snapshots[path]?.remove(line)
            return content
        }
        snapshots[path]?.remove(line)
        return snap.originalText
    }

    /** 查询 [path] 的 [line] 行是否处于折叠态（供行号栏显示 ▸/▾）。 */
    fun isFolded(path: String, line: Int): Boolean = snapshots[path]?.contains(line) == true

    /** 外部内容变化（如撤销、切文件重建）后清除 [path] 的折叠快照，避免陈旧映射误还原。 */
    fun invalidate(path: String) { snapshots.remove(path) }

    /** 清除全部折叠快照。 */
    fun clear() { snapshots.clear() }

    // ---------- 内部实现 ----------

    /** 花括号配对扫描：跳过字符串/字符/注释，遇 `{` 入栈、`}` 出栈，跨度 >= minLines 记录区间。 */
    private fun scanBraces(
        lines: List<String>, minLines: Int, tw: Int, out: LinkedHashMap<Int, Region>
    ) {
        val stack = ArrayDeque<Int>()
        var state = ScanState.CODE
        for (ln in lines.indices) {
            val s = lines[ln]
            var i = 0
            while (i < s.length) {
                val ch = s[i]
                when (state) {
                    ScanState.CODE -> when (ch) {
                        '/' -> when {
                            i + 1 < s.length && s[i + 1] == '/' -> { state = ScanState.LINE_COMMENT; i += 2 }
                            i + 1 < s.length && s[i + 1] == '*' -> { state = ScanState.BLOCK_COMMENT; i += 2 }
                            else -> i++
                        }
                        '"' -> { state = ScanState.STRING; i++ }
                        '\'' -> { state = ScanState.CHAR_LITERAL; i++ }
                        '{' -> { stack.addLast(ln); i++ }
                        '}' -> {
                            if (stack.isNotEmpty()) {
                                val start = stack.removeLast()
                                if (ln - start >= minLines) {
                                    offer(out, Region(start, ln, indentOf(lines[start], tw)))
                                }
                            }
                            i++
                        }
                        else -> i++
                    }
                    ScanState.LINE_COMMENT -> { // 行注释止于行尾，下一行回到代码态
                        i = s.length
                    }
                    ScanState.BLOCK_COMMENT -> {
                        if (ch == '*' && i + 1 < s.length && s[i + 1] == '/') { state = ScanState.CODE; i += 2 }
                        else i++
                    }
                    ScanState.STRING -> when {
                        ch == '\\' -> i += if (i + 1 < s.length) 2 else 1
                        ch == '"' -> { state = ScanState.CODE; i++ }
                        else -> i++ // 行尾未闭合字符串：下一行重置为代码态（容错）
                    }
                    ScanState.CHAR_LITERAL -> when {
                        ch == '\\' -> i += if (i + 1 < s.length) 2 else 1
                        ch == '\'' -> { state = ScanState.CODE; i++ }
                        else -> i++
                    }
                }
            }
            if (state == ScanState.LINE_COMMENT || state == ScanState.STRING || state == ScanState.CHAR_LITERAL) {
                state = ScanState.CODE // 未闭合字面量在换行处容错恢复
            }
        }
    }

    /** 缩进块扫描：行尾 `:`（Python 风格）且后续连续行缩进更深达 minLines，记为区间。 */
    private fun scanIndentBlocks(
        lines: List<String>, minLines: Int, tw: Int, out: LinkedHashMap<Int, Region>
    ) {
        var i = 0
        while (i < lines.size) {
            val trimmed = lines[i].trim()
            val base = indentOf(lines[i], tw)
            if (trimmed.isNotEmpty() && !trimmed.startsWith("#") && trimmed.endsWith(":")) {
                var j = i + 1
                var last = -1
                while (j < lines.size) {
                    val cur = lines[j]
                    if (cur.trim().isEmpty()) { j++; continue } // 空行不打断块
                    val ind = indentOf(cur, tw)
                    if (ind > base) { last = j; j++ } else break
                }
                if (last >= 0 && last - i >= minLines) {
                    offer(out, Region(i, last, base))
                }
            }
            i++
        }
    }

    /** 同一起始行保留覆盖更长的区间。 */
    private fun offer(out: LinkedHashMap<Int, Region>, region: Region) {
        val prev = out[region.startLine]
        if (prev == null || region.endLine > prev.endLine) out[region.startLine] = region
    }

    /** 前导空白列数（tab 记 tw 列）。 */
    private fun indentOf(line: String, tw: Int): Int {
        var n = 0
        var i = 0
        while (i < line.length && (line[i] == ' ' || line[i] == '\t')) {
            n += if (line[i] == '\t') tw else 1
            i++
        }
        return n
    }

    private fun placeholderText(): String =
        if (config.placeholder.isEmpty()) DEFAULT_PLACEHOLDER else config.placeholder

    /** 保留原文本的换行风格（CRLF/LF）与末尾换行。 */
    private fun joinLines(lines: List<String>, original: String): String {
        val sep = if (original.contains("\r\n")) "\r\n" else "\n"
        val trailing = original.endsWith("\n") || original.endsWith("\r")
        val body = lines.joinToString(sep)
        return if (trailing) body + sep else body
    }

    /** 与 String.split 等价但失败安全：保留行内容，兼容 \r\n 与 \n。 */
    private fun splitLines(content: String): List<String> {
        if (content.isEmpty()) return listOf("")
        return content.replace("\r\n", "\n").split('\n')
    }

    companion object {
        const val DEFAULT_MAX_REGIONS: Int = 512
        const val DEFAULT_MIN_FOLD_LINES: Int = 2
        const val DEFAULT_TAB_WIDTH: Int = 4
        const val DEFAULT_PLACEHOLDER: String = "⋯"
    }
}
