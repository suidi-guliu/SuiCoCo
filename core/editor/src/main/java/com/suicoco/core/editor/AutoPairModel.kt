package com.suicoco.core.editor

/**
 * 自动配对括号/引号（Auto Closing Pairs）内核（v0.3.34）。
 *
 * 背景：编辑器已有 [BracketMatchModel]（v0.3.20，仅做配对高亮与跳转）与
 * [AutoIndentModel]（v0.3.24，回车/Tab 缩进），但缺 VSCode 最高频的「键入左括号自动补右括号、
 * 键入右括号跳过、退格成对删除、选区包裹」能力。本内核负责纯逻辑推断，文本作为参数传入，
 * 无隐藏状态、天然幂等、可独立编译单测。
 *
 * 设计约束（与 [WordMovementModel] / [AutoIndentModel] 同口径）：
 *  - 配对表：`()` `[]` `{}` `""` `''` 与反引号；引号自身配对（左右同字符），不处理 `<>`
 *    （避免泛型/比较歧义）；`BracketMatchModel` 的跳转职责不变，二者正交。
 *  - `afterChange` 是比较 `BasicTextField.onValueChange` 的 old/new 推断编辑类型：
 *    ① 单字符插入且 old 无选区、光标紧随插入字符——左符号则在其后插入右符号、光标留中间；
 *       右符号且光标右侧恰为同一符号则「跳过」（只前移光标、不改文本）；其它字符不接管。
 *    ② 选区被单个左符号替换——包裹选区内容并保留内部选区便于连续输入。
 *  - `onBackspace` 处理光标处于成对符号中间（`(|)` / `"|"`）时一次退格删掉左右两个符号；
 *    有选区时不接管（交还默认删除选区）。
 *  - 偏移按 UTF-16 code unit 计（与 Kotlin String index / TextFieldValue.selection 一致）；
 *    不解析行结构，操作仅按偏移插入/删除，CRLF/LF/CR 换行字节原样保留。
 *  - 越界偏移夹紧、空输入/超长文本/无法推断的编辑（IME 多字符、粘贴、删除）失败安全返回 null，
 *    调用方据此保留 BasicTextField 默认行为；零 Android / Compose / 三方依赖。
 */
class AutoPairModel {

    companion object {
        /** 左符号 → 右符号；引号左右同字符。 */
        private val PAIRS = mapOf(
            '(' to ')',
            '[' to ']',
            '{' to '}',
            '"' to '"',
            '\'' to '\'',
            '`' to '`'
        )

        /** 单次处理的最大文本长度；超出直接失败安全，避免超大缓冲区卡顿。 */
        private const val MAX_TEXT_LENGTH = 2_000_000
    }

    /** 自动配对结果：新文本 + 光标/选区偏移（UTF-16）。 */
    data class Result(val text: String, val selectionStart: Int, val selectionEnd: Int = selectionStart) {
        val collapsed: Boolean get() = selectionStart == selectionEnd
    }

    /** 该字符是否为有配对的左符号。 */
    fun isOpening(c: Char): Boolean = PAIRS.containsKey(c)

    /** 该字符是否为某个配对的右符号。 */
    fun isClosing(c: Char): Boolean =
        c == ')' || c == ']' || c == '}' || c == '"' || c == '\'' || c == '`'

    /** 取左符号对应的右符号；非左符号返回 null。 */
    fun closingFor(c: Char): Char? = PAIRS[c]

    private fun clamp(text: String, offset: Int): Int = offset.coerceIn(0, text.length)

    /**
     * 推断 `newText` 相对 `oldText` 是否为可自动配对的编辑，是则返回配对后的新值。
     * 返回 null 表示不接管（调用方保留 BasicTextField 原始 next）。
     */
    fun afterChange(
        oldText: String, oldSelStart: Int, oldSelEnd: Int,
        newText: String, newSelStart: Int, newSelEnd: Int
    ): Result? {
        if (oldText.length > MAX_TEXT_LENGTH || newText.length > MAX_TEXT_LENGTH) return null
        val os = clamp(oldText, oldSelStart)
        val oe = clamp(oldText, oldSelEnd)
        val ns = clamp(newText, newSelStart)
        val ne = clamp(newText, newSelEnd)
        // 输入完成后光标应是折叠的（无选区）；带选区的输入（如拖拽替换后仍选中）不接管。
        if (ns != ne) return null

        // ① old 无选区：单字符插入。光标紧随插入字符，故 k = 光标 - 1；
        // 再校验「new 去掉 k 处字符 == old」，可消歧连续相同字符（如 `()` 中键入 `)`）。
        // 缩进/删除/IME 多字符/粘贴均不满足该校验。
        if (os == oe) {
            val k = ns - 1
            if (k < 0 || k >= newText.length) return null
            if (newText.substring(0, k) + newText.substring(k + 1) != oldText) return null
            val typed = newText[k]
            // 跳过：键入符号与光标右侧已有符号相同——只前移光标，文本不变。
            // 引号左右同字符，必须优先于此判断，否则会把「键入左引号」误判为右符号。
            if (k < oldText.length && oldText[k] == typed &&
                (isClosing(typed) || isOpening(typed))
            ) return Result(oldText, k + 1)
            // 补全：左符号（含引号）在其后插入对应右符号，光标留中间；非配对字符不接管。
            val closing = closingFor(typed) ?: return null
            val out = StringBuilder(newText).insert(k + 1, closing).toString()
            return Result(out, k + 1)
        }

        // ② old 有选区且被单个左符号替换——包裹选区内容，保留内部选区便于连续输入。
        if (newText.length != oldText.length - (oe - os) + 1) return null
        if (ns != os + 1 || os + 1 > newText.length) return null
        val typed = newText[os]
        val closing = closingFor(typed) ?: return null
        val expected = oldText.substring(0, os) + typed + oldText.substring(oe)
        if (newText != expected) return null
        val inner = oldText.substring(os, oe)
        val out = oldText.substring(0, os) + typed + inner + closing + oldText.substring(oe)
        return Result(out, os + 1, os + 1 + inner.length)
    }

    /**
     * 普通 Backspace（无选区）时，若光标正处成对符号中间（`(|)` / `"|"`），
     * 一次删掉左右两个符号；有选区、越界或不成对则返回 null（交还默认退格）。
     */
    fun onBackspace(text: String, selStart: Int, selEnd: Int): Result? {
        if (text.isEmpty() || text.length > MAX_TEXT_LENGTH) return null
        val s = clamp(text, selStart)
        val e = clamp(text, selEnd)
        if (s != e) return null
        if (s == 0 || s >= text.length) return null
        val left = text[s - 1]
        val right = text[s]
        val closing = closingFor(left) ?: return null
        if (right != closing) return null
        return Result(text.removeRange(s - 1, s + 1), s - 1)
    }

}
