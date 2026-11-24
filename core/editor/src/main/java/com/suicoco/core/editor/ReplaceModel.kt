package com.suicoco.core.editor

/**
 * 文件内查找替换（Find & Replace）内核（纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测）。
 *
 * [FindModel] 负责「找出匹配区间」，本内核负责「把匹配区间替换为新文本」：
 *  - [replaceAt] 替换一个半开区间 `[start, end)`，用于「替换当前」；
 *  - [replaceAll] 一次性替换全部匹配，用于「全部替换」，返回新文本与实际替换数；
 *  - 所有偏移按 UTF-16 code unit 计（与 Kotlin String index、[FindModel.Match] 一致）；
 *  - 替换串按字面量处理，不做正则/$ 组展开，不解释转义，避免误改用户输入；
 *  - 全部公开方法失败安全：空文本、非法区间、空匹配列表均幂等返回原文本，绝不抛异常；
 *  - 纯函数无隐藏状态，天然可单测；保持三条不变量（无 host root / 官方源 / 仅 arm64）：本类不涉及任何系统能力。
 */
class ReplaceModel {

    /** 全部替换的结果：[text] 为新文本，[replaced] 为实际替换数。 */
    data class Result(val text: String, val replaced: Int)

    /**
     * 把 [text] 的 `[start, end)` 区间替换为 [replacement]。
     * - start/end 会夹紧到 `[0, text.length]`，start > end 时按较小者作为起点；
     * - [replacement] 为空等价于删除该区间；空文本或非法区间返回原文本。
     */
    fun replaceAt(text: String, start: Int, end: Int, replacement: String): String {
        if (text.isEmpty()) return text
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(0, text.length)
        val s = minOf(from, to)
        val e = maxOf(from, to)
        if (s == e) return text
        return text.substring(0, s) + replacement + text.substring(e)
    }

    /**
     * 把 [matches] 指定的全部区间一次性替换为 [replacement]。
     * - 匹配先按起点升序排序，夹紧到文本范围，丢弃零宽与相互重叠项（保留先出现者）；
     * - 一次遍历构建结果，不会因前面的替换导致后续偏移漂移；
     * - 空文本 / 空匹配列表 / 全部匹配非法时返回 `Result(text, 0)`（幂等）。
     */
    fun replaceAll(text: String, matches: List<FindModel.Match>, replacement: String): Result {
        if (text.isEmpty() || matches.isEmpty()) return Result(text, 0)
        val accepted = ArrayList<Pair<Int, Int>>(matches.size)
        var lastEnd = 0
        for (m in matches.sortedBy { it.start }) {
            val from = m.start.coerceIn(0, text.length)
            val to = m.end.coerceIn(0, text.length)
            val s = minOf(from, to)
            val e = maxOf(from, to)
            if (e <= s || s < lastEnd) continue
            accepted.add(s to e)
            lastEnd = e
        }
        if (accepted.isEmpty()) return Result(text, 0)
        val out = StringBuilder(text.length + accepted.size * replacement.length)
        var cursor = 0
        for ((s, e) in accepted) {
            out.append(text, cursor, s)
            out.append(replacement)
            cursor = e
        }
        out.append(text, cursor, text.length)
        return Result(out.toString(), accepted.size)
    }

    /** 替换后光标应落在的位置：原匹配起点 + 替换串 UTF-16 长度。 */
    fun caretAfterReplace(matchStart: Int, replacement: String): Int =
        matchStart.coerceAtLeast(0) + replacement.length
}
