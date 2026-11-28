package com.suicoco.core.editor

/**
 * 选区行排序内核（Sort Lines，纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测）。
 *
 * 背景：VSCode 命令面板的「Sort Lines Ascending / Descending」是文本整理高频基础能力。本内核
 * 对选区覆盖的整行做稳定排序，行尾符（CRLF / LF / CR）按原位置保留——只交换行内容、不改换行符，
 * 因此混排换行文档排序后仍与原文同口径，不会引入行尾符漂移。
 *
 * 口径与约束：
 *  - 偏移为 0-based UTF-16 code unit（与 Kotlin String index / TextFieldValue.selection 一致）；
 *  - 无选区（start==end）时排序全文，有选区时排序覆盖行，选区结束恰在下一行行首时末行不计入；
 *  - 稳定排序：比较键相同的行保持原相对顺序；降序用比较器反转而非排序后整体反转，保证稳定性；
 *  - 比较键为整行内容（不含换行符），ignoreCase 时统一 lowercase；不 trim 缩进，空白行参与排序；
 *  - 排序后选区收敛到「排序块首行起始 ~ 末行内容末尾」，仍覆盖全部被排序行；
 *  - 行数上限 [MAX_LINES]、文本长度上限 [MAX_TEXT_CHARS]，空输入/越界/超容量失败安全幂等；
 *  - 纯词法行交换，不解析语义、不调用 shell、不依赖 LSP，core 不依赖 feature 不变。
 */
class SortLinesModel {

    /** 处理结果。[selectionStart]/[selectionEnd] 为同步后的选区，[changedLines] 为实际参与排序行数。 */
    data class Result(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val changedLines: Int
    )

    private data class Line(var body: String, val eol: String)

    /** 升序排序。 */
    fun ascending(
        text: String,
        selectionStart: Int = 0,
        selectionEnd: Int = text.length,
        ignoreCase: Boolean = false
    ): Result = sort(text, selectionStart, selectionEnd, descending = false, ignoreCase = ignoreCase)

    /** 降序排序。 */
    fun descending(
        text: String,
        selectionStart: Int = 0,
        selectionEnd: Int = text.length,
        ignoreCase: Boolean = false
    ): Result = sort(text, selectionStart, selectionEnd, descending = true, ignoreCase = ignoreCase)

    /** 执行排序；失败安全，永不抛异常。 */
    fun sort(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        descending: Boolean,
        ignoreCase: Boolean = false
    ): Result {
        if (text.isEmpty() || text.length > MAX_TEXT_CHARS) return unchanged(text, selectionStart, selectionEnd)
        val lines = split(text)
        if (lines.size > MAX_LINES) return unchanged(text, selectionStart, selectionEnd)
        val s = selectionStart.coerceIn(0, text.length)
        val e = selectionEnd.coerceIn(s, text.length)

        val starts = IntArray(lines.size)
        var acc = 0
        for (i in lines.indices) { starts[i] = acc; acc += lines[i].body.length + lines[i].eol.length }

        val first: Int
        var last: Int
        if (s == e) {
            first = 0
            last = lines.lastIndex
        } else {
            first = lineOfOffset(text, s).coerceIn(0, lines.lastIndex)
            last = lineOfOffset(text, e).coerceIn(first, lines.lastIndex)
            if (last > first && e == starts[last]) last--
        }
        if (first >= last) return Result(text, s, e, 0)

        val before = ArrayList<String>(last - first + 1)
        for (i in first..last) before.add(lines[i].body)
        val key = compareBy<String> { if (ignoreCase) it.lowercase() else it }
        val ordered = if (descending) before.sortedWith(key.reversed()) else before.sortedWith(key)
        if (ordered == before) return Result(text, s, e, 0)

        for (idx in first..last) lines[idx] = Line(ordered[idx - first], lines[idx].eol)
        val newText = join(lines)
        return Result(newText, starts[first], starts[last] + lines[last].body.length, last - first + 1)
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
        const val MAX_TEXT_CHARS: Int = 2_000_000
        const val MAX_LINES: Int = 2048
    }
}
