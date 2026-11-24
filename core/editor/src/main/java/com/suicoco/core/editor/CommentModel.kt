package com.suicoco.core.editor

/**
 * 行注释切换内核（Toggle Line Comment，纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测）。
 *
 * 背景：[FoldingModel] / [BracketMatchModel] / [FindModel] 已覆盖折叠、配对、查找替换，但编辑器仍缺
 * VSCode 最高频的基础编辑能力之一——行注释切换（Ctrl+/）。本内核把「对光标行或选区涉及的多行
 * 统一加注释 / 统一去注释」拆成可单测的纯逻辑：识别每行的注释符号（按语言）、保留缩进与换行符、
 * 跳过纯空白行、以「整块状态」决定加还是去，避免混合状态反复横跳。
 *
 * 口径与约束：
 *  - 偏移为 0-based UTF-16 code unit（与 Kotlin String index 一致）；行号 0-based；
 *  - 注释符号由 [tokenFor] 按文件扩展名推断（Kotlin/Java/C/JS → `//`，Python/Shell/YAML → `#`，
 *    Lua/SQL/Haskell → `--`，批处理 → `REM`，其余默认 `//`），调用方也可显式传入；
 *  - 「加注释」在行首缩进后插入 `token `（token + 一个空格），已是注释的行跳过，保证混合选区一次收敛；
 *  - 「去注释」仅当选中范围内**所有非空白行**都已是注释时执行，删除行首 token 及紧随的一个空格；
 *  - 纯空白行既不注释也不参与「全注释」判定，保持原样；
 *  - 保留 CRLF / LF / CR 原换行符与行尾空格；
 *  - 光标偏移随本行增删同步前移/后移，落在被删区间内则夹到删除起点；
 *  - 单次处理行数上限 [maxLines]，超出后夹紧结束行，避免超大文档构造过大列表；
 *  - 全部公开方法失败安全：空文本 / 越界行 / 空 token 均安全回退，绝不抛异常；纯函数幂等。
 */
class CommentModel(private val maxLines: Int = DEFAULT_MAX_LINES) {

    /** 一次切换的结果。[text] 为新文本，[caret] 为调整后的光标偏移，[changedLines] 为实际变动行数。 */
    data class Result(val text: String, val caret: Int, val changedLines: Int)

    private data class Line(var body: String, val eol: String)

    /** 按文件扩展名推断行注释符号；未识别的扩展名回退到 `//`。 */
    fun tokenFor(fileName: String): String {
        val lower = fileName.lowercase()
        val dot = lower.lastIndexOf('.')
        val ext = if (dot >= 0) lower.substring(dot + 1) else ""
        return when (ext) {
            "py", "sh", "bash", "zsh", "fish", "yaml", "yml", "toml", "ini", "cfg",
            "rb", "pl", "pm", "r", "jl", "dockerfile", "makefile", "gradle" -> "#"
            "lua", "sql", "hs", "lhs" -> "--"
            "bat", "cmd" -> "REM"
            else -> "//"
        }
    }

    /** 文本总行数（至少 1；以换行结尾时末尾空行也计一行）。 */
    fun lineCount(text: String): Int = if (text.isEmpty()) 1 else split(text).size

    /** 把 UTF-16 偏移换算为 0-based 行号，越界夹紧。 */
    fun lineOfOffset(text: String, offset: Int): Int {
        if (text.isEmpty()) return 0
        val off = offset.coerceIn(0, text.length)
        var line = 0
        for (i in 0 until off) if (text[i] == '\n') line++
        return line
    }

    /**
     * 切换 [startLine]..[endLine]（含，0-based）的注释状态。
     *
     * 若范围内所有非空白行都已是注释 → 全部去注释；否则把未注释的非空白行全部加上注释。
     * [caret] 为当前 UTF-16 光标偏移，返回值里的 [Result.caret] 已按本行增删同步调整。
     */
    fun toggle(
        text: String,
        startLine: Int,
        endLine: Int,
        token: String,
        caret: Int
    ): Result {
        if (text.isEmpty()) return Result(text, 0, 0)
        val lines = split(text)
        val cap = if (maxLines < 1) DEFAULT_MAX_LINES else maxLines
        val s = startLine.coerceIn(0, lines.lastIndex)
        val e = endLine.coerceIn(s, minOf(lines.lastIndex, s + cap - 1))
        val tok = token.trim().ifEmpty { "//" }

        val meaningful = (s..e).filter { lines[it].body.isNotBlank() }
        if (meaningful.isEmpty()) return Result(text, caret.coerceIn(0, text.length), 0)
        val allCommented = meaningful.all { isCommented(lines[it].body, tok) }

        // 每行起始偏移，用于精确调整光标。
        val starts = IntArray(lines.size)
        var acc = 0
        for (i in lines.indices) {
            starts[i] = acc
            acc += lines[i].body.length + lines[i].eol.length
        }

        val caretLine = lineOfOffset(text, caret)
        var newCaret = caret.coerceIn(0, text.length)
        var changed = 0
        for (i in meaningful) {
            val line = lines[i]
            val indentEnd = firstNonBlank(line.body)
            if (allCommented) {
                val removedLen = tok.length + if (line.body.getOrNull(indentEnd + tok.length) == ' ') 1 else 0
                val from = starts[i] + indentEnd
                val to = from + removedLen
                line.body = uncomment(line.body, tok)
                if (line.body.length > 0 || removedLen > 0) {
                    if (i == caretLine) {
                        newCaret = when {
                            caret >= to -> caret - removedLen
                            caret >= from -> from
                            else -> caret
                        }
                    }
                }
                changed++
            } else if (!isCommented(line.body, tok)) {
                val insertLen = tok.length + 1
                val at = starts[i] + indentEnd
                line.body = comment(line.body, tok)
                if (i == caretLine && caret >= at) newCaret = caret + insertLen
                changed++
            }
        }
        if (changed == 0) return Result(text, caret.coerceIn(0, text.length), 0)
        val newText = join(lines)
        return Result(newText, newCaret.coerceIn(0, newText.length), changed)
    }

    /** 单行快捷切换。 */
    fun toggleLine(text: String, line: Int, token: String, caret: Int): Result =
        toggle(text, line, line, token, caret)

    /** 判断单行是否已是注释（跳过前导空白后以 token 开头）。 */
    fun isLineCommented(text: String, line: Int, token: String): Boolean {
        val lines = split(text)
        if (line < 0 || line > lines.lastIndex) return false
        val tok = token.trim().ifEmpty { "//" }
        return isCommented(lines[line].body, tok)
    }

    private fun firstNonBlank(body: String): Int {
        var i = 0
        while (i < body.length && (body[i] == ' ' || body[i] == '\t')) i++
        return i
    }

    private fun isCommented(body: String, tok: String): Boolean {
        val i = firstNonBlank(body)
        return i < body.length && body.startsWith(tok, i)
    }

    private fun comment(body: String, tok: String): String {
        val i = firstNonBlank(body)
        if (i < body.length && body.startsWith(tok, i)) return body
        return body.substring(0, i) + tok + " " + body.substring(i)
    }

    private fun uncomment(body: String, tok: String): String {
        val i = firstNonBlank(body)
        if (i >= body.length || !body.startsWith(tok, i)) return body
        var j = i + tok.length
        if (j < body.length && body[j] == ' ') j++
        return body.substring(0, i) + body.substring(j)
    }

    /** 按 CRLF / LF / CR 拆行并保留行尾符；末尾无换行时最后一行的 eol 为空串。 */
    private fun split(text: String): List<Line> {
        val out = ArrayList<Line>()
        var i = 0
        var start = 0
        val n = text.length
        while (i < n) {
            when (text[i]) {
                '\r' -> {
                    if (i + 1 < n && text[i + 1] == '\n') {
                        out.add(Line(text.substring(start, i), "\r\n"))
                        i += 2
                    } else {
                        out.add(Line(text.substring(start, i), "\r"))
                        i += 1
                    }
                    start = i
                }
                '\n' -> {
                    out.add(Line(text.substring(start, i), "\n"))
                    i += 1
                    start = i
                }
                else -> i++
            }
        }
        out.add(Line(text.substring(start), ""))
        return out
    }

    private fun join(lines: List<Line>): String = buildString {
        for (l in lines) {
            append(l.body)
            append(l.eol)
        }
    }

    companion object {
        const val DEFAULT_MAX_LINES: Int = 512
    }
}
