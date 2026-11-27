package com.suicoco.core.editor

/**
 * 按词移动光标与按词删除（Word Movement / Delete Word）内核（v0.3.32）。
 *
 * 背景：编辑器已有 [CursorLayoutModel]（偏移→行列像素）、[GoToLineModel]（按行跳转）、
 * [LineOperationsModel]（行级移动/复制/删除）等「按行」能力，但缺 VSCode 最高频的
 * 「按词」光标移动（Ctrl+←/→）与按词删除（Ctrl+Backspace / Ctrl+Delete）。本内核负责纯逻辑：
 * 词边界判定、光标目标偏移计算、删除后的文本与光标偏移。文本作为参数传入，内核无隐藏状态，
 * 天然幂等、可独立编译单测。
 *
 * 设计约束（与 [AutoIndentModel] / [CaseModel] / [LineOperationsModel] 同口径）：
 *  - 偏移按 UTF-16 code unit 计（与 Kotlin String index / `TextFieldValue.selection` 一致）；
 *  - 字符分三类：空白（isWhitespace）、词字符（letter/digit/underscore）、其它标点；
 *    连续同类非空白字符视为一个「词块」，词边界落在类别切换处；
 *  - 左移：先向左跨过紧邻空白，再向左跨过同类词块到块首；右移对称，停在块尾（开区间端点）；
 *  - 有选区时：左/右移动把光标折叠到选区起点/终点再计算；删除则直接删掉整个选区（退格语义）；
 *  - 越界偏移夹紧到 [0, text.length]，空输入/超长文本/无可删内容失败安全返回 null，绝不抛异常；
 *  - CRLF / LF / 孤立 CR 均按空白处理（一次换行即一段空白），不破坏换行符原字节；
 *  - 零 Android / Compose / 三方依赖，core 不依赖 feature 的约束不变。
 */
class WordMovementModel {

    companion object {
        const val MOVE_LEFT = 0
        const val MOVE_RIGHT = 1
        const val DELETE_BEFORE = 2
        const val DELETE_AFTER = 3

        /** 单次处理的最大文本长度；超出直接失败安全，避免超大缓冲区卡顿。 */
        private const val MAX_TEXT_LENGTH = 2_000_000
    }

    /** 删除结果：新文本 + 新光标偏移（UTF-16）。 */
    data class Result(val text: String, val caret: Int)

    /** 字符类别：0=空白，1=词字符（字母/数字/下划线），2=其它标点。 */
    private fun kindOf(c: Char): Int = when {
        c.isWhitespace() -> 0
        c.isLetterOrDigit() || c == '_' -> 1
        else -> 2
    }

    private fun clamp(text: String, offset: Int): Int = offset.coerceIn(0, text.length)

    /**
     * 光标左侧的词首偏移。先跨过紧邻空白，再向左跨过同类词块；停在块首。
     * 偏移越界夹紧；空文本返回 0。
     */
    fun wordLeft(text: String, offset: Int): Int {
        if (text.isEmpty()) return 0
        var i = clamp(text, offset)
        if (i == 0) return 0
        while (i > 0 && kindOf(text[i - 1]) == 0) i-- // 跨过左侧紧邻空白
        if (i == 0) return 0
        val kind = kindOf(text[i - 1])
        while (i > 0 && kindOf(text[i - 1]) == kind) i-- // 跨过同类词块
        return i
    }

    /**
     * 光标右侧的词尾偏移（开区间端点，供删除使用）。先跨过紧邻空白，再向右跨过同类词块。
     */
    fun wordRight(text: String, offset: Int): Int {
        if (text.isEmpty()) return 0
        var i = clamp(text, offset)
        val len = text.length
        if (i >= len) return len
        while (i < len && kindOf(text[i]) == 0) i++
        if (i >= len) return len
        val kind = kindOf(text[i])
        while (i < len && kindOf(text[i]) == kind) i++
        return i
    }

    /**
     * 光标所在（或紧邻左侧）词块的半开区间 [start, end)。
     * 光标落在空白上且左侧也是空白（或文首）时返回 null，便于 UI 判定「无词可选」。
     */
    fun wordRangeAt(text: String, offset: Int): IntRange? {
        if (text.isEmpty()) return null
        val i = clamp(text, offset)
        // 文末：取紧邻左侧的词块
        if (i >= text.length) {
            if (i == 0 || kindOf(text[i - 1]) == 0) return null
            var s = i
            val k = kindOf(text[s - 1])
            while (s > 0 && kindOf(text[s - 1]) == k) s--
            return s until i
        }
        // 光标落在空白上：取紧邻左侧的词块（若有）
        if (kindOf(text[i]) == 0) {
            if (i == 0 || kindOf(text[i - 1]) == 0) return null
            var s = i
            val k = kindOf(text[s - 1])
            while (s > 0 && kindOf(text[s - 1]) == k) s--
            return s until i
        }
        // 光标落在词块内：向左右扩展到类别切换处
        val k = kindOf(text[i])
        var s = i
        var e = i
        while (s > 0 && kindOf(text[s - 1]) == k) s--
        while (e < text.length && kindOf(text[e]) == k) e++
        return s until e
    }

    /**
     * Ctrl+Backspace：有选区（selStart != selEnd）时删掉整个选区；否则删掉光标左侧词块
     * （含与左侧紧邻的空白）。无内容可删、越界或超长文本返回 null，调用方据此不拦截按键。
     */
    fun deleteWordBefore(text: String, selStart: Int, selEnd: Int): Result? {
        if (text.length > MAX_TEXT_LENGTH) return null
        val s = clamp(text, selStart)
        val e = clamp(text, selEnd)
        if (s != e) return Result(text.removeRange(s, e), s)
        val from = wordLeft(text, s)
        if (from == s) return null
        return Result(text.removeRange(from, s), from)
    }

    /**
     * Ctrl+Delete：有选区时删掉整个选区；否则删掉光标右侧词块（含与右侧紧邻的空白）。
     */
    fun deleteWordAfter(text: String, selStart: Int, selEnd: Int): Result? {
        if (text.length > MAX_TEXT_LENGTH) return null
        val s = clamp(text, selStart)
        val e = clamp(text, selEnd)
        if (s != e) return Result(text.removeRange(s, e), s)
        val to = wordRight(text, e)
        if (to == e) return null
        return Result(text.removeRange(e, to), e)
    }
}
