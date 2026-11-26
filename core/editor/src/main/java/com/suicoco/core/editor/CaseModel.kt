package com.suicoco.core.editor

/**
 * 大小写转换（Transform Case）内核（v0.3.28）。
 *
 * 把 VSCode 常用的文本大小写转换收敛为纯 Kotlin 计算：转大写 / 转小写 / 首字母大写（句首式）/
 * 标题式 / 驼峰 / 蛇形 / 短横线。既有编辑器已覆盖行操作、注释、折叠、大纲、查找替换、自动缩进，
 * 但文案与标识符的大小写仍需手工逐字改写，本内核补齐这一基础能力。
 *
 * 设计约束（与 [LineOperationsModel] / [CommentModel] 等同口径）：
 *  - 无选区时取光标所在（或紧邻左侧）的「词」，有选区时对选区生效；
 *  - 原样保留选区外的全部字符，换行符 CRLF / LF / 孤立 CR 逐字节保留，不跨行合并单词；
 *  - 直接改字母大小写的操作保留所有非字母字符；词式操作（camel/snake/kebab）仅重建每行
 *    首个词到末个词之间的片段，片段内的分隔符按目标风格归一化，片段前后的标点与行尾原样保留；
 *  - 偏移按 UTF-16 code unit 计，越界夹紧、空输入/非法操作失败安全，纯函数幂等（同输入输出恒定）；
 *  - 零 Android / Compose / 三方依赖，core 不依赖 feature 的架构约束不变。
 */
class CaseModel {

    /** 转换结果：[start, end) 为转换后新文本中目标片段的区间，便于 UI 同步选区/光标。 */
    data class Result(val text: String, val start: Int, val end: Int, val caret: Int)

    /** 半开区间 [start, end)。 */
    data class Range(val start: Int, val end: Int)

    companion object {
        const val UPPER = 0
        const val LOWER = 1
        const val CAPITALIZE = 2
        const val TITLE = 3
        const val CAMEL = 4
        const val SNAKE = 5
        const val KEBAB = 6

        private const val MAX_SPAN = 1 shl 16 // 单次转换 64K 字符上限，防止超大选区卡顿
    }

    /** 词字符：Unicode 字母/数字与下划线，与标识符 token 口径一致。 */
    fun isWordChar(c: Char): Boolean = c == '_' || Character.isLetterOrDigit(c)

    /**
     * 取光标所在词；光标落在非词字符（分隔符）上时返回空区间，表示无可转换的单词。
     * 光标紧跟在词尾（如 "abc|"）时向左取词，符合输入习惯。
     */
    fun wordRangeOf(text: String, offset: Int): Range {
        val len = text.length
        if (len == 0) return Range(0, 0)
        val at = offset.coerceIn(0, len)
        if (at > 0 && isWordChar(text[at - 1])) {
            var s = at - 1
            while (s > 0 && isWordChar(text[s - 1])) s--
            var e = at - 1
            while (e < len && isWordChar(text[e])) e++
            return Range(s, e)
        }
        if (at < len && isWordChar(text[at])) {
            var s = at
            while (s > 0 && isWordChar(text[s - 1])) s--
            var e = at
            while (e < len && isWordChar(text[e])) e++
            return Range(s, e)
        }
        return Range(at, at)
    }

    /** 将标识符/短语拆为单词：下划线、连字符与空白是分隔符，另外识别大小写边界。 */
    private fun splitWords(raw: String): List<String> {
        val words = ArrayList<String>()
        val cur = StringBuilder()
        fun flush() {
            if (cur.isNotEmpty()) {
                words.add(cur.toString())
                cur.setLength(0)
            }
        }
        for (i in raw.indices) {
            val c = raw[i]
            if (!Character.isLetterOrDigit(c)) {
                flush()
                continue
            }
            if (cur.isNotEmpty() && isBoundary(cur[cur.length - 1], c, raw, i)) flush()
            cur.append(c)
        }
        flush()
        return words
    }

    /** lower→Upper 及 HTTP+Server 这类缩略词边界。 */
    private fun isBoundary(prev: Char, current: Char, raw: String, index: Int): Boolean {
        if (Character.isLowerCase(prev) && Character.isUpperCase(current)) return true
        if (Character.isDigit(prev) && Character.isUpperCase(current)) return true
        return Character.isUpperCase(prev) && Character.isUpperCase(current) &&
            index + 1 < raw.length && Character.isLowerCase(raw[index + 1])
    }

    private fun lowerOf(s: String): String = buildString(s.length) {
        for (c in s) append(c.lowercaseChar())
    }

    private fun upperOf(s: String): String = buildString(s.length) {
        for (c in s) append(c.uppercaseChar())
    }

    private fun capitalizeOf(s: String): String {
        if (s.isEmpty()) return s
        return s[0].uppercaseChar() + lowerOf(s.substring(1))
    }

    /** 直接改字母大小写：保留所有非字母（含换行、标点、缩进与下划线）。 */
    private fun transformLetters(piece: String, from: Int, to: Int, op: Int): String {
        val out = StringBuilder(piece.length + 8)
        var i = 0
        while (i < piece.length) {
            val c = piece[i]
            if (i in from until to && Character.isLetter(c)) {
                out.append(
                    when (op) {
                        UPPER -> c.uppercaseChar()
                        LOWER -> c.lowercaseChar()
                        else -> c
                    }
                )
            } else {
                out.append(c)
            }
            i++
        }
        return out.toString()
    }

    /** 按词元改大小写：CAPITALIZE 只大写首个词（句首式），TITLE 每个词首字母都大写。 */
    private fun transformTokens(piece: String, from: Int, to: Int, op: Int): String {
        val out = StringBuilder(piece.length + 8)
        var cursor = 0
        var first = true
        while (cursor < piece.length) {
            if (cursor !in from until to || !isWordChar(piece[cursor])) {
                out.append(piece[cursor])
                cursor++
                continue
            }
            var end = cursor + 1
            while (end < to && isWordChar(piece[end])) end++
            val token = piece.substring(cursor, end)
            out.append(
                when (op) {
                    TITLE -> capitalizeOf(token)
                    CAPITALIZE -> if (first) capitalizeOf(token) else lowerOf(token)
                    else -> token
                }
            )
            first = false
            cursor += token.length
        }
        return out.toString()
    }

    /** 词式转换：仅重建每行首个词到末个词之间的片段，行首/行尾标点与换行原样保留。 */
    private fun transformWordStyle(piece: String, from: Int, to: Int, op: Int): String {
        var s = from
        while (s < to && !isWordChar(piece[s])) s++
        var e = to
        while (e > s && !isWordChar(piece[e - 1])) e--
        if (s >= e) return piece
        val words = splitWords(piece.substring(s, e))
        if (words.isEmpty()) return piece
        val joined = when (op) {
            CAMEL -> buildString {
                words.forEachIndexed { i, w -> append(if (i == 0) lowerOf(w) else capitalizeOf(w)) }
            }
            SNAKE -> words.joinToString("_") { lowerOf(it) }
            KEBAB -> words.joinToString("-") { lowerOf(it) }
            else -> piece.substring(s, e)
        }
        return piece.substring(0, s) + joined + piece.substring(e)
    }

    private fun transformPiece(piece: String, from: Int, to: Int, op: Int): String = when (op) {
        UPPER, LOWER -> transformLetters(piece, from, to, op)
        CAPITALIZE, TITLE -> transformTokens(piece, from, to, op)
        CAMEL, SNAKE, KEBAB -> transformWordStyle(piece, from, to, op)
        else -> piece
    }

    /**
     * 对选区（或光标词）执行转换；返回 null 表示无有效选区或非法操作。
     * [start]/[end] 经夹紧与交换后生效；多行选区逐行处理，不跨行拼接单词。
     */
    fun apply(text: String, start: Int, end: Int, op: Int): Result? {
        if (op < UPPER || op > KEBAB || text.isEmpty()) return null
        val len = text.length
        var s = start.coerceIn(0, len)
        var e = end.coerceIn(0, len)
        if (s > e) { val t = s; s = e; e = t }
        if (e - s > MAX_SPAN) return null
        if (s == e) {
            val word = wordRangeOf(text, s)
            if (word.start == word.end) return null
            s = word.start
            e = word.end
        }

        val changed = StringBuilder(e - s + 16)
        var from = s
        while (from < e) {
            // 仅切分选区内的换行；CRLF 作为单个分隔符，不进入任何词式转换片段。
            var eol = from
            while (eol < e && text[eol] != '\r' && text[eol] != '\n') eol++
            changed.append(transformPiece(text.substring(from, eol), 0, eol - from, op))
            if (eol >= e) break
            if (text[eol] == '\r' && eol + 1 < e && text[eol + 1] == '\n') {
                changed.append("\r\n")
                from = eol + 2
            } else {
                changed.append(text[eol])
                from = eol + 1
            }
        }
        val newText = text.substring(0, s) + changed.toString() + text.substring(e)
        val newEnd = s + changed.length
        return Result(newText, s, newEnd, newEnd)
    }
}
