package com.suicoco.core.editor

/**
 * 行操作（Line Operations）内核（v0.3.26）。
 *
 * 背景：编辑器已有自动缩进、跳转行、注释切换、折叠、大纲等能力，但缺少 VSCode 最高频的
 * 基础行编辑——上移行 / 下移行（Alt+↑/↓）、复制行（Ctrl+D）、删除行（Ctrl+Shift+K）。
 * 本内核只负责纯文本计算：把文档拆为「行内容 + 行间换行符」两部分，行操作只重排行内容，
 * 换行符按其在文档中的位置保留，因此 CRLF / LF / 孤立 CR 混排文档在移动/复制/删除后
 * 仍保持原有的换行符序列；行首缩进随行内容整体移动，不重新计算。
 *
 * 设计约束（与 [GoToLineModel] / [AutoIndentModel] / [CommentModel] 同口径）：
 *  - 所有行号 0-based，偏移与列按 UTF-16 code unit 计（与 Kotlin String index 一致）；
 *  - 文本与光标作为参数传入，内核无隐藏状态，同输入输出恒定，天然可独立编译单测；
 *  - 越界行号/光标夹紧到合法范围，空输入、非法操作、超容量一律返回 null（失败安全幂等）；
 *  - 光标随行块移动/增删同步：块内光标跟着块走，被跨越的光标落到移动后的对应位置，
 *    列尽量保留并夹紧到新行长度；
 *  - 零 Android / Compose / 三方依赖，core 不依赖 feature 的约束不变。
 */
class LineOperationsModel {

    /** 操作结果：新文本与同步后的光标偏移。 */
    data class Result(val text: String, val caret: Int)

    /** 供 UI 复用的操作类型常量。 */
    companion object {
        const val MOVE_UP = 0
        const val MOVE_DOWN = 1
        const val DUPLICATE = 2
        const val DELETE = 3

        private const val MAX_LINES = 4096
    }

    /** 行内容 + 行间换行符（长度恒为 lines.size - 1）。 */
    private data class Document(val lines: List<String>, val seps: List<String>) {
        fun join(): String = buildString {
            for (i in lines.indices) {
                append(lines[i])
                if (i < seps.size) append(seps[i])
            }
        }
    }

    /** 拆分文本：CRLF / LF / 孤立 CR 各算一次换行；空文本视作一个空行。超容量返回 null。 */
    private fun split(text: String): Document? {
        val lines = ArrayList<String>()
        val seps = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\r' -> {
                    if (lines.size >= MAX_LINES) return null
                    lines.add(text.substring(start, i))
                    if (i + 1 < text.length && text[i + 1] == '\n') {
                        seps.add("\r\n")
                        i++
                    } else {
                        seps.add("\r")
                    }
                    i++
                    start = i
                }
                '\n' -> {
                    if (lines.size >= MAX_LINES) return null
                    lines.add(text.substring(start, i))
                    seps.add("\n")
                    i++
                    start = i
                }
                else -> i++
            }
        }
        if (lines.size >= MAX_LINES) return null
        lines.add(text.substring(start))
        return Document(lines, seps)
    }

    private fun lineIndexOf(doc: Document, offset: Int): Int {
        var pos = 0
        for (i in doc.seps.indices) {
            pos += doc.lines[i].length + doc.seps[i].length
            if (offset < pos) return i
        }
        return doc.lines.lastIndex.coerceAtLeast(0)
    }

    private fun startOf(doc: Document, line: Int): Int {
        var pos = 0
        val last = doc.lines.lastIndex.coerceAtLeast(0)
        val safe = line.coerceIn(0, last)
        for (i in 0 until safe) pos += doc.lines[i].length + doc.seps[i].length
        return pos
    }

    /** 文本行数：CRLF / LF / 孤立 CR 均算一次换行；空文本按 1 行计。 */
    fun lineCount(text: String): Int = split(text)?.lines?.size ?: MAX_LINES + 1

    /** 偏移所在 0-based 行号；越界偏移夹到末行。 */
    fun lineOfOffset(text: String, offset: Int): Int {
        val doc = split(text) ?: return 0
        return lineIndexOf(doc, offset.coerceAtLeast(0))
    }

    /** 0-based 行首 UTF-16 偏移；越界行夹到末行。 */
    fun lineStartOf(text: String, line: Int): Int {
        val doc = split(text) ?: return 0
        return startOf(doc, line)
    }

    /** 0-based 行、列（UTF-16 code unit）对应的偏移；列夹到该行行尾。 */
    fun offsetOf(text: String, line: Int, column: Int): Int {
        val doc = split(text) ?: return 0
        val safe = line.coerceIn(0, doc.lines.lastIndex.coerceAtLeast(0))
        val start = startOf(doc, safe)
        return start + column.coerceAtLeast(0).coerceAtMost(doc.lines[safe].length)
    }

    /** 执行行操作；失败（空文本/非法操作/超容量/越界不可移动）返回 null。 */
    fun apply(text: String, startLine: Int, endLine: Int, caret: Int, op: Int): Result? {
        if (text.isEmpty()) return null
        val doc = split(text) ?: return null
        if (doc.lines.isEmpty()) return null
        if (startLine < 0 && endLine < 0) return null
        val a = startLine.coerceAtLeast(0).coerceAtMost(doc.lines.lastIndex)
        val b = endLine.coerceAtLeast(0).coerceAtMost(doc.lines.lastIndex)
        val start = minOf(a, b)
        val end = maxOf(a, b)
        val size = end - start + 1

        val newLines: ArrayList<String>
        val newSeps: ArrayList<String>
        val caretLine: Int
        val oldLine = lineIndexOf(doc, caret.coerceAtLeast(0))
        val oldColumn = (caret.coerceAtLeast(0) - startOf(doc, oldLine))
            .coerceAtLeast(0).coerceAtMost(doc.lines[oldLine].length)

        when (op) {
            MOVE_UP -> {
                if (start == 0) return null
                newLines = ArrayList(doc.lines)
                val block = newLines.subList(start, end + 1).toList()
                repeat(size) { newLines.removeAt(start) }
                newLines.addAll(start - 1, block)
                newSeps = ArrayList(doc.seps) // 换行符留在原位，只搬行内容
                caretLine = when (oldLine) {
                    in (start - 1) until start -> end
                    in start..end -> oldLine - 1
                    else -> oldLine
                }
            }
            MOVE_DOWN -> {
                if (end >= doc.lines.lastIndex) return null
                newLines = ArrayList(doc.lines)
                val block = newLines.subList(start, end + 1).toList()
                repeat(size) { newLines.removeAt(start) }
                newLines.addAll(start + 1, block)
                newSeps = ArrayList(doc.seps)
                caretLine = when (oldLine) {
                    end + 1 -> start
                    in start..end -> oldLine + size
                    else -> oldLine
                }
            }
            DUPLICATE -> {
                newLines = ArrayList(doc.lines)
                val block = newLines.subList(start, end + 1).toList()
                newLines.addAll(end + 1, block)
                newSeps = ArrayList(doc.seps)
                // 在块末尾补入 size 条换行符，模板取块所在位置原有的分隔符，
                // 末行无换行时用其前一条（或 LF）兜底，保持局部换行风格。
                val template = (start..end).map { i ->
                    if (i < doc.seps.size) doc.seps[i]
                    else doc.seps.lastOrNull() ?: "\n"
                }
                newSeps.addAll(end.coerceAtMost(newSeps.size), template)
                caretLine = when (oldLine) {
                    in start..end -> end + 1 + (oldLine - start) // 光标跟到副本对应行
                    in (end + 1)..doc.lines.lastIndex -> oldLine + size
                    else -> oldLine
                }
            }
            DELETE -> {
                newLines = ArrayList(doc.lines)
                repeat(size) { newLines.removeAt(start) }
                newSeps = ArrayList(doc.seps)
                val removed = HashSet<Int>()
                // 删除 size 行要同时删掉 size 条分隔：块首为 0 时删块内及块后，
                // 否则删块前及块内；统一保持「剩余分隔数 = 剩余行数 - 1」。
                val sepFrom = if (start == 0) 0 else start - 1
                val sepTo = if (start == 0) end else end - 1
                for (i in sepFrom..sepTo) if (i in newSeps.indices) removed.add(i)
                // 注意 removeAt 会移动索引，统一从大到小删除。
                for (i in removed.sortedDescending()) newSeps.removeAt(i)
                caretLine = when {
                    oldLine < start -> oldLine
                    oldLine > end -> oldLine - size
                    else -> start // 被删除行上的光标落到删除点
                }.coerceAtMost(newLines.lastIndex.coerceAtLeast(0))
            }
            else -> return null
        }

        val next = Document(newLines, newSeps)
        val newText = next.join()
        val newCaret = offsetOf(newText, caretLine, oldColumn)
        return Result(newText, newCaret)
    }

    fun moveUp(text: String, startLine: Int, endLine: Int, caret: Int): Result? =
        apply(text, startLine, endLine, caret, MOVE_UP)

    fun moveDown(text: String, startLine: Int, endLine: Int, caret: Int): Result? =
        apply(text, startLine, endLine, caret, MOVE_DOWN)

    fun duplicate(text: String, startLine: Int, endLine: Int, caret: Int): Result? =
        apply(text, startLine, endLine, caret, DUPLICATE)

    fun delete(text: String, startLine: Int, endLine: Int, caret: Int): Result? =
        apply(text, startLine, endLine, caret, DELETE)
}
