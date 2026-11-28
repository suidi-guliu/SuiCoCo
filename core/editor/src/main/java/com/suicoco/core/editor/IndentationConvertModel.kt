package com.suicoco.core.editor

/**
 * 缩进风格转换内核（Convert Indentation，纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测）。
 *
 * 背景：VSCode 的「Convert Indentation to Spaces / Tabs」是格式化高频基础能力；[IndentSelectionModel]
 * 只负责选区整体增删一级缩进，[AutoIndentModel] 只负责回车/单行 Tab，均不改变已有缩进风格。本内核
 * 把「行首缩进在 tab 与空格之间无损互换」拆为可单测的纯逻辑，只动行首前导缩进，不碰行内字符。
 *
 * 口径与约束：
 *  - 偏移为 0-based UTF-16 code unit（与 Kotlin String index / TextFieldValue.selection 一致）；
 *  - 前导缩进先折算为「视觉列」：tab 使列前进到下一个 tabWidth 的倍数，空格前进 1 列；
 *  - 转空格：前导缩进整体替换为 col 个空格；转 tab：替换为 col/tabWidth 个 tab + col%tabWidth 个空格，
 *    因此 `toSpaces(toTabs(x))` 在 tabWidth 一致时按视觉列往返一致；
 *  - 无选区（start==end）时处理全文，有选区时仅处理选区覆盖的行，选区结束恰在下一行行首时末行不计入；
 *  - 选区偏移按「行内相对位移 + 该行增量」映射，行内相对位移夹紧到新行长度，永不越界；
 *  - CRLF / LF / CR 原样保留，UTF-16 中文按 code unit 计数；
 *  - tabWidth 非法回退 4，单次行数上限 [MAX_LINES]、文本长度上限 [MAX_TEXT_CHARS]，
 *    空输入/越界/超容量失败安全幂等，零 Android/Compose/三方依赖，core 不依赖 feature 不变。
 */
class IndentationConvertModel {

    /** 处理结果。[selectionStart]/[selectionEnd] 为同步后的选区，[changedLines] 为实际变动行数。 */
    data class Result(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val changedLines: Int
    )

    private data class Line(var body: String, val eol: String)

    /** 把选区（或全文）行首缩进转为空格。 */
    fun toSpaces(
        text: String,
        tabWidth: Int = DEFAULT_TAB_WIDTH,
        selectionStart: Int = 0,
        selectionEnd: Int = text.length
    ): Result = apply(text, tabWidth, selectionStart, selectionEnd, spaces = true)

    /** 把选区（或全文）行首缩进转为 tab（不足一个 tabWidth 的余数保留为空格）。 */
    fun toTabs(
        text: String,
        tabWidth: Int = DEFAULT_TAB_WIDTH,
        selectionStart: Int = 0,
        selectionEnd: Int = text.length
    ): Result = apply(text, tabWidth, selectionStart, selectionEnd, spaces = false)

    /** 执行转换；失败安全，永不抛异常。 */
    fun apply(
        text: String,
        tabWidth: Int,
        selectionStart: Int,
        selectionEnd: Int,
        spaces: Boolean
    ): Result {
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS) return unchanged(text, selectionStart, selectionEnd)
        val lines = split(text)
        if (lines.size > MAX_LINES) return unchanged(text, selectionStart, selectionEnd)
        val width = if (tabWidth in 1..8) tabWidth else DEFAULT_TAB_WIDTH
        val s = selectionStart.coerceIn(0, text.length)
        val e = selectionEnd.coerceIn(s, text.length)

        val oldStarts = IntArray(lines.size)
        var acc = 0
        for (i in lines.indices) { oldStarts[i] = acc; acc += lines[i].body.length + lines[i].eol.length }

        val startLine: Int
        var endLine: Int
        if (s == e) {
            startLine = 0
            endLine = lines.lastIndex
        } else {
            startLine = lineOfOffset(text, s).coerceIn(0, lines.lastIndex)
            endLine = lineOfOffset(text, e).coerceIn(startLine, lines.lastIndex)
            if (endLine > startLine && e == oldStarts[endLine]) endLine--
        }

        val deltas = IntArray(lines.size)
        var changed = 0
        for (i in startLine..endLine) {
            val body = lines[i].body
            val lead = body.takeWhile { it == ' ' || it == '\t' }
            if (lead.isEmpty()) continue
            val col = visualColumns(lead, width)
            val newLead = if (spaces) " ".repeat(col)
                          else "\t".repeat(col / width) + " ".repeat(col % width)
            if (newLead == lead) continue
            lines[i] = Line(newLead + body.substring(lead.length), lines[i].eol)
            deltas[i] = newLead.length - lead.length
            changed++
        }
        if (changed == 0) return Result(text, s, e, 0)

        val newStarts = IntArray(lines.size)
        acc = 0
        for (i in lines.indices) { newStarts[i] = acc; acc += lines[i].body.length + lines[i].eol.length }
        val newText = join(lines)

        fun mapOffset(old: Int): Int {
            val i = lineOfOffset(text, old).coerceIn(0, lines.lastIndex)
            val rel = old - oldStarts[i]
            val newRel = (rel + deltas[i]).coerceIn(0, lines[i].body.length)
            return (newStarts[i] + newRel).coerceIn(0, newText.length)
        }

        return Result(newText, mapOffset(s), mapOffset(e), changed)
    }

    /** 前导缩进的视觉列：tab 前进到下一个 tabWidth 倍数，空格前进 1。 */
    private fun visualColumns(lead: String, width: Int): Int {
        var col = 0
        for (c in lead) col = if (c == '\t') col + width - col % width else col + 1
        return col
    }

    private fun unchanged(text: String, s: Int, e: Int) = Result(
        text, s.coerceIn(0, text.length), e.coerceIn(0, text.length), 0
    )

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

    /** 按 CRLF / LF / CR 拆行并保留行尾符；末尾无换行时最后一行 eol 为空串。 */
    private fun split(text: String): MutableList<Line> {
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
