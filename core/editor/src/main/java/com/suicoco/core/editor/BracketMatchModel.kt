package com.suicoco.core.editor

/**
 * 括号配对内核（纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测）。
 *
 * 背景：[FindModel] 解决「找得到、替换得掉」，`EditorArea` 已有 caret / 多段选区 / 查找高亮，
 * 但光标落在 `(`、`)`、`[`、`]`、`{`、`}` 上时，编辑器不会指出它的另一半。本内核把
 * VSCode 最基础的「括号匹配高亮 + 跳转到配对括号」拆成可单测的纯逻辑：扫描文本得到代码区
 * 括号 token（跳过字符串、字符字面量、`//` 与 `/* */` 注释），用栈配对，再按光标偏移取匹配。
 *
 * 口径与约束：
 *  - 偏移为 0-based UTF-16 code unit（与 Kotlin String index 一致）；
 *  - 只处理 `()[]{}`，不处理 `<>`（泛型/比较运算符歧义大），不做 LSP 语义配对；
 *  - 匹配结果给出两个**单字符半开区间** `[openStart,openStart+1)` / `[closeStart,closeStart+1)`，
 *    可直接交给 [SelectionHighlightModel] 拆成逐行高亮矩形；
 *  - 注释与字面量内的括号忽略；未闭合字符串在换行处容错恢复到代码态；
 *  - 括号数量上限 [maxBrackets]，避免超大文档构造过大列表；
 *  - 全部公开方法失败安全：空文本 / 越界偏移 / 非法上限均安全回退，绝不抛异常；纯函数幂等。
 */
class BracketMatchModel(private val maxBrackets: Int = DEFAULT_MAX_BRACKETS) {

    /** 扫描到的一个代码区括号 token。 */
    data class Bracket(val offset: Int, val char: Char, val isOpen: Boolean)

    /** 一对已配对的括号，记录各自的起始偏移（各占 1 个 UTF-16 code unit）。 */
    data class Match(val openStart: Int, val closeStart: Int) {
        val openRange: Pair<Int, Int> get() = openStart to openStart + 1
        val closeRange: Pair<Int, Int> get() = closeStart to closeStart + 1
    }

    private enum class ScanState { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR_LITERAL }

    /**
     * 扫描文本中所有**代码区**括号 token（跳过字符串、字符字面量与注释）。
     * 结果按偏移升序；达到 [maxBrackets] 后停止，避免长文档生成超大列表。
     */
    fun bracketsOf(text: String): List<Bracket> {
        val len = text.length
        if (len == 0) return emptyList()
        val cap = if (maxBrackets < 1) DEFAULT_MAX_BRACKETS else maxBrackets
        val out = ArrayList<Bracket>()
        var state = ScanState.CODE
        var i = 0
        while (i < len && out.size < cap) {
            val ch = text[i]
            when (state) {
                ScanState.CODE -> when (ch) {
                    '/' -> when {
                        i + 1 < len && text[i + 1] == '/' -> { state = ScanState.LINE_COMMENT; i += 2 }
                        i + 1 < len && text[i + 1] == '*' -> { state = ScanState.BLOCK_COMMENT; i += 2 }
                        else -> i++
                    }
                    '"' -> { state = ScanState.STRING; i++ }
                    '\'' -> { state = ScanState.CHAR_LITERAL; i++ }
                    '(', '[', '{' -> { out.add(Bracket(i, ch, true)); i++ }
                    ')', ']', '}' -> { out.add(Bracket(i, ch, false)); i++ }
                    else -> i++
                }
                ScanState.LINE_COMMENT -> {
                    if (ch == '\n' || ch == '\r') state = ScanState.CODE
                    i++
                }
                ScanState.BLOCK_COMMENT -> {
                    if (ch == '*' && i + 1 < len && text[i + 1] == '/') { state = ScanState.CODE; i += 2 }
                    else i++
                }
                ScanState.STRING -> when {
                    ch == '\\' -> i += if (i + 1 < len) 2 else 1
                    ch == '"' -> { state = ScanState.CODE; i++ }
                    ch == '\n' || ch == '\r' -> { state = ScanState.CODE; i++ } // 未闭合字符串行尾容错
                    else -> i++
                }
                ScanState.CHAR_LITERAL -> when {
                    ch == '\\' -> i += if (i + 1 < len) 2 else 1
                    ch == '\'' -> { state = ScanState.CODE; i++ }
                    ch == '\n' || ch == '\r' -> { state = ScanState.CODE; i++ }
                    else -> i++
                }
            }
        }
        return out
    }

    /** 返回所有成功配对的括号对，按左括号偏移升序。失配项不出现在结果中。 */
    fun matchesOf(text: String): List<Match> {
        val brackets = bracketsOf(text)
        if (brackets.isEmpty()) return emptyList()
        val paired = pairIndices(brackets)
        val out = ArrayList<Match>()
        for (i in brackets.indices) {
            if (!brackets[i].isOpen) continue
            val j = paired[i]
            if (j >= 0 && j < brackets.size) out.add(Match(brackets[i].offset, brackets[j].offset))
        }
        return out
    }

    /** 文本中的括号是否全部配对（忽略字符串/注释中的符号）。 */
    fun isBalanced(text: String): Boolean = unmatchedOf(text).isEmpty()

    /** 返回所有未配对的括号偏移，升序；可用于状态栏提示。 */
    fun unmatchedOf(text: String): List<Int> {
        val brackets = bracketsOf(text)
        if (brackets.isEmpty()) return emptyList()
        val paired = pairIndices(brackets)
        val out = ArrayList<Int>()
        for (i in brackets.indices) if (paired[i] < 0) out.add(brackets[i].offset)
        return out
    }

    /**
     * 取光标偏移处的配对结果。光标夹在括号两侧都算命中：
     * 先看左侧 `offset-1`（刚输入完 `(` 的常见场景），再看右侧 `offset`。
     * 仅命中已成功配对的括号；失配或字符串/注释中的符号返回 null。
     */
    fun matchAt(text: String, offset: Int): Match? {
        val len = text.length
        if (len == 0) return null
        val off = clamp(offset, len)
        val brackets = bracketsOf(text)
        if (brackets.isEmpty()) return null
        val paired = pairIndices(brackets)
        val byOffset = HashMap<Int, Int>(brackets.size * 2)
        for (i in brackets.indices) byOffset[brackets[i].offset] = i
        for (candidate in intArrayOf(off - 1, off)) {
            if (candidate < 0 || candidate >= len) continue
            val idx = byOffset[candidate] ?: continue
            val other = paired[idx]
            if (other < 0) continue
            val open = if (brackets[idx].isOpen) brackets[idx] else brackets[other]
            val close = if (brackets[idx].isOpen) brackets[other] else brackets[idx]
            return Match(open.offset, close.offset)
        }
        return null
    }

    /**
     * 跳转到配对括号：光标在左括号处 → 返回右括号之后；在右括号处 → 返回左括号之后。
     * 无匹配时原样返回夹紧后的偏移，保证幂等。
     */
    fun jumpToMatch(text: String, offset: Int): Int {
        val off = clamp(offset, text.length)
        val match = matchAt(text, off) ?: return off
        return when (off) {
            match.openStart, match.openStart + 1 -> match.closeStart + 1
            match.closeStart, match.closeStart + 1 -> match.openStart + 1
            else -> off
        }
    }

    /** 用数组栈做括号配对，返回与 [brackets] 等长的配对下标数组，未配对为 -1。 */
    private fun pairIndices(brackets: List<Bracket>): IntArray {
        val result = IntArray(brackets.size) { -1 }
        if (brackets.isEmpty()) return result
        val stack = IntArray(brackets.size)
        var sp = 0
        for (i in brackets.indices) {
            val b = brackets[i]
            if (b.isOpen) {
                stack[sp++] = i
            } else if (sp > 0) {
                val top = stack[sp - 1]
                if (pairsMatch(brackets[top].char, b.char)) {
                    sp--
                    result[top] = i
                    result[i] = top
                }
                // 失配时保留栈顶：不与后面的括号强行配对，行为可预测。
            }
        }
        return result
    }

    private fun pairsMatch(open: Char, close: Char): Boolean =
        (open == '(' && close == ')') || (open == '[' && close == ']') || (open == '{' && close == '}')

    private fun clamp(offset: Int, textLength: Int): Int {
        val upper = maxOf(0, textLength)
        return when {
            offset < 0 -> 0
            offset > upper -> upper
            else -> offset
        }
    }

    companion object {
        const val DEFAULT_MAX_BRACKETS: Int = 4096
    }
}
