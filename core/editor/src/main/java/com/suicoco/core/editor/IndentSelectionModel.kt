package com.suicoco.core.editor

/**
 * 多行选区整体缩进 / 反缩进内核（Indent Selection，纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测）。
 *
 * 背景：[AutoIndentModel] 仅处理回车与单行光标 Tab/Shift+Tab；VSCode 最高频的基础能力之一——
 * 「选中多行后按 Tab 整体缩进、Shift+Tab 整体反缩进」此前缺失。本内核把「按行拆分选区、推断
 * tab/空格风格与宽度、逐行缩进/反缩进、选区与光标偏移同步」拆为可单测的纯逻辑。
 *
 * 口径与约束：
 *  - 偏移为 0-based UTF-16 code unit（与 Kotlin String index / TextFieldValue.selection 一致）；行号 0-based；
 *  - [Options] 由 [optionsFor] 从文档前若干非空行推断：以 tab 前导则 useTabs=true，否则取最小正前导空格数（1..8）为 tabWidth，默认 4；
 *  - 缩进：对选区覆盖的每一行在行首插入一个缩进单元（`\t` 或 tabWidth 个空格），空行同样处理（与 VSCode「缩进行」一致）；
 *  - 反缩进：每行行首若有 tab 则去掉一个 tab，否则去掉 min(tabWidth, 前导空格数) 个空格，不足则去掉全部前导空格；
 *  - 选区结束恰好落在某行行首（且选区非折叠）时，末行不计入，与常见编辑器行为一致；
 *  - 选区偏移按「行内相对位移 + 该行之前各行的累计增量」映射，反缩进被吃掉的前导空格夹紧到行首，不会越界；
 *  - CRLF / LF / CR 原样保留，不解析行结构以外的语义；UTF-16 中文按 code unit 计数；
 *  - 单次行数上限 [MAX_LINES]、文本长度上限 [MAX_TEXT_CHARS]，超出后安全回退、绝不抛异常；纯函数幂等。
 */
class IndentSelectionModel {

    /** 缩进风格与宽度。 */
    data class Options(val useTabs: Boolean = false, val tabWidth: Int = DEFAULT_TAB_WIDTH) {
        fun safeTabWidth(): Int = if (tabWidth in 1..8) tabWidth else DEFAULT_TAB_WIDTH
        fun unit(): String = if (useTabs) "\t" else " ".repeat(safeTabWidth())
    }

    /** 处理结果。[selectionStart]/[selectionEnd] 为同步后的选区（UTF-16），[changedLines] 为实际变动行数。 */
    data class Result(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val changedLines: Int
    )

    private data class Line(var body: String, val eol: String)

    /** 从文档推断缩进风格：以 tab 前导的行占多数则用 tab，否则取最小正前导空格数（1..8）为宽度。 */
    fun optionsFor(text: String): Options {
        if (text.isEmpty()) return Options()
        var tabVotes = 0
        var spaceVotes = 0
        var minSpaces = Int.MAX_VALUE
        for (line in split(text)) {
            val lead = line.body.takeWhile { it == ' ' || it == '\t' }
            if (lead.isEmpty() || line.body.length == lead.length) continue // 跳过空行/纯缩进行
            if (lead.contains('\t')) tabVotes++ else {
                spaceVotes++
                if (lead.length < minSpaces) minSpaces = lead.length
            }
        }
        if (tabVotes > spaceVotes && tabVotes > 0) return Options(useTabs = true)
        val width = if (minSpaces in 1..8) minSpaces else DEFAULT_TAB_WIDTH
        return Options(useTabs = false, tabWidth = width)
    }

    /** 把 UTF-16 偏移换算为 0-based 行号，越界夹紧。CRLF/LF/CR 同口径。 */
    fun lineOfOffset(text: String, offset: Int): Int {
        if (text.isEmpty()) return 0
        val off = offset.coerceIn(0, text.length)
        var line = 0
        var i = 0
        while (i < off) {
            when (text[i]) {
                '\r' -> { line++; if (i + 1 < off && text[i + 1] == '\n') i++ }
                '\n' -> line++
            }
            i++
        }
        return line
    }

    /** 缩进（outdent=false）或反缩进（outdent=true）选区覆盖的所有行。失败安全，永不抛异常。 */
    fun apply(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        options: Options,
        outdent: Boolean
    ): Result {
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS) {
            return Result(text, selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length), 0)
        }
        val lines = split(text)
        if (lines.size > MAX_LINES) {
            return Result(text, selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length), 0)
        }
        val s = selectionStart.coerceIn(0, text.length)
        val e = selectionEnd.coerceIn(s, text.length)

        // 逐行起始偏移（原文本）。
        val oldStarts = IntArray(lines.size)
        var acc = 0
        for (i in lines.indices) { oldStarts[i] = acc; acc += lines[i].body.length + lines[i].eol.length }

        var startLine = lineOfOffset(text, s).coerceIn(0, lines.lastIndex)
        var endLine = lineOfOffset(text, e).coerceIn(startLine, lines.lastIndex)
        // 选区结束恰在下一行行首时不计入该行。
        if (s != e && endLine > startLine && e == oldStarts[endLine]) endLine--

        val unit = options.unit()
        val width = options.safeTabWidth()
        val deltas = IntArray(lines.size)
        var changed = 0
        for (i in startLine..endLine) {
            val line = lines[i]
            if (!outdent) {
                line.body = unit + line.body
                deltas[i] = unit.length
                changed++
            } else {
                val lead = line.body.takeWhile { it == ' ' || it == '\t' }
                if (lead.isEmpty()) continue
                val remove = if (lead[0] == '\t') 1 else minOf(width, lead.length)
                line.body = line.body.substring(remove)
                deltas[i] = -remove
                changed++
            }
        }
        if (changed == 0) return Result(text, s, e, 0)

        // 新行起始偏移。
        val newStarts = IntArray(lines.size)
        acc = 0
        for (i in lines.indices) { newStarts[i] = acc; acc += lines[i].body.length + lines[i].eol.length }
        val newText = join(lines)

        fun mapOffset(old: Int): Int {
            val i = lineOfOffset(text, old).coerceIn(0, lines.lastIndex)
            val rel = old - oldStarts[i]
            val delta = deltas[i]
            val newRel = maxOf(0, rel + delta)
            return (newStarts[i] + newRel).coerceIn(0, newText.length)
        }

        return Result(newText, mapOffset(s), mapOffset(e), changed)
    }

    /** 缩进快捷方式。 */
    fun indent(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        options: Options = optionsFor(text)
    ): Result = apply(text, selectionStart, selectionEnd, options, outdent = false)

    /** 反缩进快捷方式。 */
    fun outdent(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        options: Options = optionsFor(text)
    ): Result = apply(text, selectionStart, selectionEnd, options, outdent = true)

    /** 按 CRLF / LF / CR 拆行并保留行尾符；末尾无换行时最后一行 eol 为空串。 */
    private fun split(text: String): List<Line> {
        val out = ArrayList<Line>()
        var i = 0
        var start = 0
        val n = text.length
        while (i < n) {
            when (text[i]) {
                '\r' -> {
                    if (i + 1 < n && text[i + 1] == '\n') {
                        out.add(Line(text.substring(start, i), "\r\n")); i += 2
                    } else {
                        out.add(Line(text.substring(start, i), "\r")); i += 1
                    }
                    start = i
                }
                '\n' -> { out.add(Line(text.substring(start, i), "\n")); i += 1; start = i }
                else -> i++
            }
        }
        out.add(Line(text.substring(start), ""))
        return out
    }

    private fun join(lines: List<Line>): String = buildString {
        for (l in lines) { append(l.body); append(l.eol) }
    }

    companion object {
        const val DEFAULT_TAB_WIDTH: Int = 4
        const val MAX_TEXT_CHARS: Int = 2_000_000
        const val MAX_LINES: Int = 2048
    }
}
