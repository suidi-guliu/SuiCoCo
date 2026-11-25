package com.suicoco.core.editor

/**
 * 跳转到指定行（Go to Line，Ctrl+G）内核（v0.3.25）。
 *
 * 背景：编辑器已有 [FindModel]（文件内查找）、[SymbolOutlineModel]（代码大纲）等「按内容定位」能力，
 * 但尚无 VSCode 最高频的「按行号/行:列直接跳转」。本内核负责纯逻辑部分：解析 `行[:列]` 输入、
 * 1-based → 0-based 换算、按文档行数夹紧、计算 UTF-16 光标偏移。文本作为参数传入，内核无隐藏状态，
 * 天然幂等、可独立编译单测。
 *
 * 设计约束（与 [CursorLayoutModel] / [AutoIndentModel] 同口径）：
 *  - 偏移与列均按 UTF-16 code unit 计（与 Kotlin String index 一致）；
 *  - CRLF / LF / 孤立 CR 均算一次换行，offset 停在 CRLF 中间时仍属上一行行尾；
 *  - 接受 `行` 或 `行[:列]`（分隔符 `:` / `,`），列省略时按第 1 列；只接受十进制正整数；
 *  - 行号超出文档末行夹紧到末行，列超出该行长度夹紧到行尾；非法输入返回 null，绝不抛异常；
 *  - [resolve] 返回的 [Result.clamped] 告知 UI 是否发生夹紧，便于给出提示；
 *  - 零 Android / Compose / 三方依赖，core 不依赖 feature 的约束不变。
 */
class GoToLineModel {

    /** 解析后的 1-based 目标：行、列均为正整数。 */
    data class Target(val line: Int, val column: Int)

    /** 夹紧后的定位结果：line/column 为 0-based，offset 为 UTF-16 偏移。 */
    data class Result(
        val offset: Int,
        val line: Int,
        val column: Int,
        val clamped: Boolean
    )

    companion object {
        private const val MAX_DIGITS = 9 // 单段最多 9 位十进制数字，避免溢出与超长输入
        private const val DEFAULT_COLUMN = 1
    }

    /**
     * 解析 `行` 或 `行[:列]`；分隔符接受 `:` 与 `,`，列省略时取第 1 列。
     * 两端空白忽略，中间出现空白/符号/多分隔符/非数字一律失败；0 与负数失败。
     * 失败返回 null（失败安全，调用方据此给出提示而不修改文档）。
     */
    fun parse(input: String): Target? {
        if (input.isEmpty()) return null
        val s = input.trim()
        if (s.isEmpty() || s.length > MAX_DIGITS * 2 + 1) return null

        var sep = -1
        for (i in s.indices) {
            val c = s[i]
            if (c == ':' || c == ',') {
                if (sep >= 0) return null // 多个分隔符
                sep = i
            } else if (c !in '0'..'9') {
                return null // 空白、正负号、小数点等一律非法
            }
        }

        val linePart: String
        val colPart: String
        if (sep < 0) {
            linePart = s
            colPart = DEFAULT_COLUMN.toString()
        } else {
            if (sep == 0 || sep == s.length - 1) return null // 缺行号或缺列号
            linePart = s.substring(0, sep)
            colPart = s.substring(sep + 1)
        }
        if (linePart.length > MAX_DIGITS || colPart.length > MAX_DIGITS) return null

        val line = linePart.toIntOrNull() ?: return null
        val column = colPart.toIntOrNull() ?: return null
        if (line < 1 || column < 1) return null
        return Target(line, column)
    }

    /** 文本行数：CRLF / LF / 孤立 CR 均算一次换行；空文本按 1 行计。 */
    fun lineCount(text: String): Int {
        if (text.isEmpty()) return 1
        var count = 1
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\r' -> {
                    count++
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                }
                '\n' -> count++
            }
            i++
        }
        return count
    }

    /** 0-based 行号对应的行起始偏移；CRLF 只前进一次，越界行夹到文末。 */
    private fun lineStartOf(text: String, line0: Int): Int {
        var offset = 0
        var line = 0
        while (line < line0 && offset < text.length) {
            when (text[offset]) {
                '\r' -> {
                    line++
                    offset++
                    if (offset < text.length && text[offset] == '\n') offset++
                }
                '\n' -> {
                    line++
                    offset++
                }
                else -> offset++
            }
        }
        return offset.coerceAtMost(text.length)
    }

    /** 行起始偏移处到行尾（不含换行符）的偏移。 */
    private fun lineEndOf(text: String, start: Int): Int {
        var end = start
        while (end < text.length && text[end] != '\r' && text[end] != '\n') end++
        return end
    }

    /**
     * 1-based 行/列 → UTF-16 光标偏移。行越界夹到末行，列越界夹到行尾；
     * 空文本返回 0。列按 code unit 计（与 [CursorLayoutModel] 的偏移口径一致）。
     */
    fun offsetOf(text: String, line: Int, column: Int): Int {
        if (text.isEmpty()) return 0
        val safeLine = line.coerceIn(1, lineCount(text))
        val start = lineStartOf(text, safeLine - 1)
        val end = lineEndOf(text, start)
        val safeColumn = column.coerceAtLeast(1)
        return (start + safeColumn - 1).coerceAtMost(end)
    }

    /**
     * 解析输入并夹紧到 [text]：返回 0-based line/column 与光标 offset。
     * 行/列任一项被夹紧时 [Result.clamped] 为 true；非法输入返回 null。
     */
    fun resolve(text: String, input: String): Result? {
        val target = parse(input) ?: return null
        val safeLine = target.line.coerceIn(1, lineCount(text))
        val offset = offsetOf(text, safeLine, target.column)
        val start = lineStartOf(text, safeLine - 1)
        val actualColumn = (offset - start).coerceAtLeast(0)
        return Result(
            offset = offset,
            line = safeLine - 1,
            column = actualColumn,
            clamped = safeLine != target.line || actualColumn != target.column - 1
        )
    }
}
