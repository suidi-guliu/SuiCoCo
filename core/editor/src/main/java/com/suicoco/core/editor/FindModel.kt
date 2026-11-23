package com.suicoco.core.editor

import kotlin.math.min

/**
 * 文件内查找（Find-in-file）内核（纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测）。
 *
 * 背景：编辑器已有 [UndoTree]（撤销）、[MinimapModel]（缩略图）、[MultiCursorModel]（多光标）、
 * [CursorLayoutModel]（偏移→行列）、[SelectionHighlightModel]（选区→高亮矩形），但尚无「在文件内
 * 搜索某个字符串并逐个跳转」的能力。本内核负责纯逻辑部分：给定文本与查询串，计算全部匹配区间，
 * 并提供「下一个 / 上一个」的循环索引。文本作为参数传入，内核无隐藏状态，天然幂等、可单测。
 *
 * 设计约束（与 [CursorLayoutModel] / [SelectionHighlightModel] 同口径）：
 *  - 偏移按 UTF-16 code unit 计（与 Kotlin String index 一致），匹配区间为半开 `[start, end)`；
 *  - 支持大小写敏感 / 忽略大小写（[Options.ignoreCase]）与全词匹配（[Options.wholeWord]，
 *    词字符口径与 [SelectionHighlightModel] 的 token 一致：字母 / 数字 / `_`）；
 *  - 匹配互不重叠、按 start 升序，总数上限 [maxMatches]，避免长文档画爆；
 *  - 「下一个 / 上一个」在匹配列表上循环，空列表返回 -1，不抛异常；
 *  - 所有公开方法失败安全：空查询、空文本、非法索引均安全回退，绝不抛异常；
 *  - 保持三条不变量（无 host root / 官方源 / 仅 arm64）：本类不涉及任何系统能力。
 */
class FindModel(private val maxMatches: Int = DEFAULT_MAX_MATCHES) {

    /** 一个匹配：半开区间 [start, end)，均为 0-based UTF-16 偏移。 */
    data class Match(val start: Int, val end: Int)

    /** 查找选项：是否忽略大小写、是否全词匹配。 */
    data class Options(
        val ignoreCase: Boolean = false,
        val wholeWord: Boolean = false
    )

    private val cap: Int
        get() = if (maxMatches < 1) DEFAULT_MAX_MATCHES else maxMatches

    /**
     * 返回 [text] 中所有与 [query] 匹配的区间，互不重叠、按 start 升序。
     * - [query] 为空或 [text] 为空时返回空列表（幂等）；
     * - [Options.ignoreCase] 为 true 时忽略大小写；[Options.wholeWord] 为 true 时仅匹配完整词；
     * - 结果数量不超过 [maxMatches]。
     */
    fun findAll(text: String, query: String, options: Options = Options()): List<Match> {
        if (query.isEmpty() || text.isEmpty()) return emptyList()
        val qlen = query.length
        if (qlen > text.length) return emptyList()
        val result = ArrayList<Match>()
        var from = 0
        while (from <= text.length - qlen) {
            val idx = text.indexOf(query, from, options.ignoreCase)
            if (idx < 0) break
            val end = idx + qlen
            if (!options.wholeWord || isWordBoundary(text, idx, end)) {
                result.add(Match(idx, end))
                if (result.size >= cap) break
            }
            // 前进一个完整查询长度，保证匹配互不重叠；idx+qlen > idx（qlen>0）恒前进
            from = end
        }
        return result
    }

    /**
     * 给定当前匹配索引 [current]（-1 表示尚无当前项），返回下一个匹配索引（循环）。
     * 空列表返回 -1；[current] 超界时按上界夹紧后取下一个。
     */
    fun nextIndex(matches: List<Match>, current: Int): Int {
        if (matches.isEmpty()) return -1
        val size = matches.size
        val safe = if (current < 0) -1 else min(current, size - 1)
        return (safe + 1) % size
    }

    /**
     * 给定当前匹配索引 [current]（-1 表示尚无当前项），返回上一个匹配索引（循环）。
     * 空列表返回 -1；[current] 为 -1 时跳到末尾；超界时按上界夹紧后取上一个。
     */
    fun previousIndex(matches: List<Match>, current: Int): Int {
        if (matches.isEmpty()) return -1
        val size = matches.size
        val safe = if (current < 0) 0 else min(current, size - 1)
        return (safe - 1 + size) % size
    }

    /**
     * 返回第一个 start >= [offset] 的匹配索引；全部小于 offset 时循环回 0。
     * 用于「光标处开始查找」。空列表返回 -1。
     */
    fun indexAtOrAfter(matches: List<Match>, offset: Int): Int {
        if (matches.isEmpty()) return -1
        for (i in matches.indices) {
            if (matches[i].start >= offset) return i
        }
        return 0
    }

    /**
     * 返回最后一个 start <= [offset] 的匹配索引；全部大于 offset 时循环回末尾。
     * 用于「向上查找的起点」。空列表返回 -1。
     */
    fun indexAtOrBefore(matches: List<Match>, offset: Int): Int {
        if (matches.isEmpty()) return -1
        for (i in matches.indices.reversed()) {
            if (matches[i].start <= offset) return i
        }
        return matches.size - 1
    }

    /** 取当前索引处的匹配；索引非法或列表为空时返回 null。 */
    fun matchAt(matches: List<Match>, index: Int): Match? =
        if (index in matches.indices) matches[index] else null

    /**
     * 把匹配区间转换为 [SelectionHighlightModel] 可直接消费的 (start,end) 对，
     * 便于 UI 复用现有高亮矩形内核，不重复实现行列/像素换算。
     */
    fun asSelectionPairs(matches: List<Match>): List<Pair<Int, Int>> =
        matches.map { it.start to it.end }

    /** 全词边界：区间左右两侧都不能是词字符。 */
    private fun isWordBoundary(text: String, start: Int, end: Int): Boolean {
        val beforeOk = start == 0 || !isWordChar(text[start - 1])
        val afterOk = end == text.length || !isWordChar(text[end])
        return beforeOk && afterOk
    }

    private fun isWordChar(ch: Char): Boolean =
        ch == '_' || ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'

    companion object {
        const val DEFAULT_MAX_MATCHES: Int = 2048
    }
}
