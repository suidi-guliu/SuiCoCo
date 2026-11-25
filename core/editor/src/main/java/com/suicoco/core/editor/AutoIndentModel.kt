package com.suicoco.core.editor

/**
 * 自动缩进内核（v0.3.24）：把 VSCode 最常用的「回车延续缩进 / Tab 缩进 / Shift+Tab 反缩进」
 * 收敛为纯 Kotlin 标准库实现，零 Android / Compose / 三方依赖，可独立编译单测。
 *
 * 设计约束：
 * - 只做词法级判断（跳过字符串/字符字面量与行尾注释），不依赖 LSP/解析器；
 * - 保留原换行符（CRLF / LF / CR），保留原缩进风格（tab 或空格）；
 * - 容量上限与 clamp 保护，空文本/越界偏移/非法配置一律失败安全返回原文本；
 * - 返回 [Result] 由调用方写回缓冲区，因此撤销树、补全、minimap 等既有行为自动同步。
 */
class AutoIndentModel(private val maxIndentColumns: Int = DEFAULT_MAX_INDENT) {

    companion object {
        private const val DEFAULT_MAX_INDENT = 64
    }

    /** 缩进参数；unit 为新增一级缩进，tabWidth 用于 Tab 键对齐到下一制表位。 */
    data class Options(
        val unit: String = "    ",
        val tabWidth: Int = 4,
        val braceBased: Boolean = true,
        val colonBased: Boolean = true
    ) {
        fun safeUnit(): String =
            if (unit.isEmpty() || unit.length > 8 || unit.any { it == '\n' || it == '\r' }) "    " else unit

        fun safeTabWidth(): Int = if (tabWidth in 1..8) tabWidth else 4
    }

    /** 编辑结果：text 为完整新文本，caret 为夹紧后的新光标偏移。 */
    data class Result(val text: String, val caret: Int)

    /** 空文本、越界 offset 一律夹紧；非法上限回退默认值。 */
    private fun safeMax(): Int = if (maxIndentColumns in 1..512) maxIndentColumns else DEFAULT_MAX_INDENT
    private fun clampOffset(text: String, offset: Int): Int = offset.coerceIn(0, text.length)
    private fun capped(indent: String): String =
        if (indent.length <= safeMax()) indent else indent.take(safeMax())

    /** 0-based 行号；CRLF 只算一次换行，孤立 CR/LF 均算换行，越界 offset 夹到文末。 */
    fun lineOfOffset(text: String, offset: Int): Int {
        val o = clampOffset(text, offset)
        var line = 0
        var i = 0
        while (i < o) {
            when (text[i]) {
                '\r' -> {
                    line++
                    if (i + 1 < o && text[i + 1] == '\n') i++
                }
                '\n' -> line++
            }
            i++
        }
        return line
    }

    /** 当前行起始偏移（0-based），CRLF / LF / CR 均视为行分隔。 */
    fun lineStartOf(text: String, offset: Int): Int {
        var i = clampOffset(text, offset) - 1
        while (i >= 0 && text[i] != '\n' && text[i] != '\r') i--
        if (i >= 0 && text[i] == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
        return i + 1
    }

    /** 当前行首个非空白字符之前的缩进串（空格/tab 混合原样保留）。 */
    fun indentOf(text: String, offset: Int): String {
        val start = lineStartOf(text, offset)
        var i = start
        while (i < text.length && (text[i] == ' ' || text[i] == '\t')) i++
        return text.substring(start, i)
    }

    /** 文本采用的换行符；无换行时默认 LF。 */
    fun newlineOf(text: String): String {
        if (text.contains("\r\n")) return "\r\n"
        val i = text.indexOf('\n')
        if (i >= 0) return "\n"
        if (text.indexOf('\r') >= 0) return "\r"
        return "\n"
    }

    private fun unitFor(indent: String, options: Options): String =
        if (indent.contains('\t')) "\t" else options.safeUnit()

    /** 去掉一级缩进：tab 行退一个 tab，空格行退一个 unit，混合行至少退一列。 */
    private fun dedent(indent: String, options: Options): String {
        if (indent.isEmpty()) return indent
        val unit = unitFor(indent, options)
        if (unit.length <= indent.length && indent.endsWith(unit)) return indent.dropLast(unit.length)
        return indent.dropLast(1)
    }

    /**
     * 去掉行尾注释后的代码部分，供「以 { / : 结尾」判断。
     * 双引号、单引号、反引号内的 `//` 与 `#` 不当注释；`//` 只识别两个连续斜杠；
     * `#` 仅在行首或前有空白时视为注释（Python/Shell），避免误伤 `#fff` 之类字面量。
     */
    private fun codeHeadOf(line: String): String {
        var quote = '\u0000'
        var escaped = false
        var cut = line.length
        for (i in line.indices) {
            val c = line[i]
            if (quote != '\u0000') {
                if (escaped) escaped = false
                else if (c == '\\') escaped = true
                else if (c == quote) quote = '\u0000'
                continue
            }
            when (c) {
                '"', '\'', '`' -> quote = c
                '/' -> if (i + 1 < line.length && line[i + 1] == '/') { cut = i; break }
                '#' -> if (i == 0 || line[i - 1].isWhitespace()) { cut = i; break }
            }
        }
        return line.substring(0, cut).trimEnd()
    }

    /** 光标右侧同一行的下一个非空白字符；到行尾返回 null。 */
    private fun nextCodeChar(text: String, caret: Int): Char? {
        var i = caret
        while (i < text.length && text[i] != '\n' && text[i] != '\r') {
            if (!text[i].isWhitespace()) return text[i]
            i++
        }
        return null
    }

    /**
     * 回车自动缩进：在 caret 处插入换行 + 新缩进。
     * - 上一行代码以 `{ [ ( -> =>` 或 `:` 结尾 → 新行多一级；
     * - 光标右侧紧跟闭合括号 → 新行退一级；若上一行同时开启括号（`{|}`），
     *   则插入「新行+一级缩进」与「闭合行+原缩进」两段，光标停在前者；
     * - 其余情况只延续当前缩进；空白行与失败场景返回原文。
     */
    fun onEnter(text: String, caret: Int, options: Options = Options()): Result {
        val c = clampOffset(text, caret)
        if (text.isEmpty()) return Result(text, 0)
        val nl = newlineOf(text)
        val lineStart = lineStartOf(text, c)
        val beforeLine = text.substring(lineStart, c)
        val base = capped(indentOf(text, c))
        val unit = unitFor(base, options)
        val head = codeHeadOf(beforeLine)
        val next = nextCodeChar(text, c)

        val opens = options.braceBased &&
            (head.endsWith('{') || head.endsWith('[') || head.endsWith('(') ||
                head.endsWith("->") || head.endsWith("=>"))
        val colonOpens = options.colonBased && head.endsWith(':')
        val closes = options.braceBased && (next == '}' || next == ']' || next == ')')

        val newIndent = when {
            closes && !opens -> dedent(base, options)
            opens || colonOpens -> capped(base + unit)
            else -> base
        }
        val closingTail = if (closes && opens) nl + base else ""
        val inserted = nl + newIndent + closingTail
        val newText = text.substring(0, c) + inserted + text.substring(c)
        return Result(newText, c + inserted.length - closingTail.length)
    }

    /**
     * Tab 键缩进：前缀全为空白时对齐到下一制表位（tab 行插入一个 tab），
     * 否则插入一级缩进；[outdent] 为 Shift+Tab，删除当前行一级前导缩进。
     * 非折叠选区场景由调用方保证，内核只处理单个偏移。
     */
    fun onTab(text: String, caret: Int, options: Options = Options(), outdent: Boolean = false): Result {
        val c = clampOffset(text, caret)
        val start = lineStartOf(text, c)
        val prefix = text.substring(start, c)
        val unit = unitFor(prefix, options)

        if (outdent) {
            var end = c
            while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
            val leading = text.substring(start, end).takeWhile { it == ' ' || it == '\t' }
            if (leading.isEmpty()) return Result(text, c)
            val remove = if (leading.contains('\t')) 1 else unit.length.coerceAtMost(leading.length)
            val newLeading = leading.dropLast(remove)
            val newText = text.substring(0, start) + newLeading + text.substring(start + leading.length)
            return Result(newText, (c - remove).coerceIn(start, newText.length))
        }

        val insert = if (prefix.all { it == ' ' || it == '\t' }) {
            if (prefix.contains('\t')) "\t"
            else " ".repeat(options.safeTabWidth() - prefix.length % options.safeTabWidth())
        } else unit
        val newText = text.substring(0, c) + insert + text.substring(c)
        return Result(newText, c + insert.length)
    }
}
