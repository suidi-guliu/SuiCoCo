package com.suicoco.core.editor

import kotlin.math.max
import kotlin.math.min

/**
 * 逐光标多段选区高亮内核（纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测）。
 *
 * 背景：[CursorLayoutModel] 只把光标偏移映射为 caret 竖线，[MultiCursorModel] 只管理光标偏移集合；
 * 二者都不渲染「选区」。本内核把一组选区（anchor/active 偏移对）换算为逐行的高亮矩形，供编辑器
 * 在文本域叠加多条半透明选区块——即 VSCode「多个选择区域同时高亮」的纯逻辑部分。文本作为参数
 * 传入，内核无隐藏状态，天然幂等、可单测。
 *
 * 设计约束（与 [CursorLayoutModel] / [MinimapModel] 同口径）：
 *  - 行/列均 0-based；列按 UTF-16 code unit 计（与 Kotlin String index 一致），tab 按 [tabWidth] 加权；
 *  - CRLF（`\r\n`）视为单个换行，offset 落在 `\r` 与 `\n` 之间时归到该行行尾；孤立 `\r` 视为换行；
 *  - 选区先按 [start,end) 规范：anchor>active 交换、夹紧到 `[0, text.length]`、丢弃零宽、
 *    排序后合并相邻/重叠区间，避免同一字符被多段高亮重复绘制；
 *  - 矩形数量有上限 [maxRects]，跨行选区逐行拆分也计入，避免长文档画爆；
 *  - 所有公开方法失败安全：非法 tabWidth / 像素尺寸回退，空输入幂等，绝不抛异常；
 *  - 保持三条不变量（无 host root / 官方源 / 仅 arm64）：本类不涉及任何系统能力。
 */
class SelectionHighlightModel(private val maxRects: Int = DEFAULT_MAX_RECTS) {

    /** 一个规范化后的选区：半开区间 [start, end)，均为 0-based UTF-16 偏移。 */
    data class Selection(val start: Int, val end: Int)

    /** 一行上的一段高亮矩形（不含文本区 padding，由 UI 叠加）。 */
    data class HighlightRect(
        val line: Int,
        val startColumn: Int,
        val endColumn: Int,
        val xPx: Float,
        val widthPx: Float,
        val yPx: Float
    )

    private val cap: Int
        get() = if (maxRects < 1) DEFAULT_MAX_RECTS else maxRects

    /**
     * 把一组 (anchor, active) 偏移对规范化为有序、不重叠的 [Selection] 列表。
     * - 每对交换为 start<=end；夹紧到 `[0, text.length]`；
     * - 丢弃零宽（start==end）选区；按 start 升序；
     * - 相邻（prev.end==next.start）与重叠（prev.end>=next.start）合并为一段；
     * - 选区数量无硬上限（高亮矩形在 [highlightRects] 阶段统一限额）。
     */
    fun normalize(text: String, pairs: List<Pair<Int, Int>>): List<Selection> {
        if (pairs.isEmpty()) return emptyList()
        val len = max(0, text.length)
        val raw = ArrayList<Selection>(pairs.size)
        for (p in pairs) {
            var a = clamp(p.first, len)
            var b = clamp(p.second, len)
            if (a > b) { val t = a; a = b; b = t }
            if (a < b) raw.add(Selection(a, b))
        }
        if (raw.isEmpty()) return emptyList()
        raw.sortWith(compareBy<Selection> { it.start }.thenBy { it.end })
        val merged = ArrayList<Selection>(raw.size)
        var cur = raw[0]
        for (i in 1 until raw.size) {
            val next = raw[i]
            if (next.start <= cur.end) {
                cur = Selection(cur.start, max(cur.end, next.end))
            } else {
                merged.add(cur)
                cur = next
            }
        }
        merged.add(cur)
        return merged
    }

    /**
     * 把规范化选区列表拆成逐行高亮矩形。
     * - 单行选区：[startCol, endCol)；
     * - 跨行选区：首行 [startCol, 行尾]、中间行整行、末行 [0, endCol)；
     * - 列按 tab 加权（[tabWidth]，非法回退 4）；CRLF 视为单换行；
     * - `xPx = startColumn * charWidthPx`、`widthPx = (endColumn-startColumn)*charWidthPx`、
     *   `yPx = line*lineHeightPx`；非法像素尺寸（NaN/负）回退 0；
     * - 零宽行段跳过；矩形总数上限 [maxRects]；空输入返回空列表。
     */
    fun highlightRects(
        text: String,
        selections: List<Selection>,
        lineHeightPx: Float,
        charWidthPx: Float,
        tabWidth: Int = DEFAULT_TAB_WIDTH
    ): List<HighlightRect> {
        if (selections.isEmpty()) return emptyList()
        val lh = finiteOrZero(lineHeightPx)
        val cw = finiteOrZero(charWidthPx)
        val tab = if (tabWidth <= 0) DEFAULT_TAB_WIDTH else tabWidth
        val result = ArrayList<HighlightRect>()
        for (sel in selections) {
            if (result.size >= cap) break
            val s = clamp(sel.start, text.length)
            val e = clamp(sel.end, text.length)
            if (s >= e) continue
            val ls = lineColumnOf(text, s, tab)
            val le = lineColumnOf(text, e, tab)
            if (ls.line == le.line) {
                addRect(result, ls.line, ls.column, le.column, cw, lh)
            } else {
                // 首行：从 startCol 到该行可视行尾
                addRect(result, ls.line, ls.column, lineVisualEnd(text, ls.line, tab), cw, lh)
                // 中间整行
                for (ln in (ls.line + 1) until le.line) {
                    if (result.size >= cap) break
                    addRect(result, ln, 0, lineVisualEnd(text, ln, tab), cw, lh)
                }
                // 末行：从行首到 endCol
                if (result.size < cap) addRect(result, le.line, 0, le.column, cw, lh)
            }
        }
        return result
    }

    /**
     * 便捷入口：直接给 (anchor, active) 偏移对，一步得到高亮矩形。
     * 等价于 [normalize] 后再 [highlightRects]。
     */
    fun highlightOf(
        text: String,
        pairs: List<Pair<Int, Int>>,
        lineHeightPx: Float,
        charWidthPx: Float,
        tabWidth: Int = DEFAULT_TAB_WIDTH
    ): List<HighlightRect> = highlightRects(
        text, normalize(text, pairs), lineHeightPx, charWidthPx, tabWidth
    )

    /**
     * 取 [offset] 所在的连续「标识符 token」区间 [start,end)，供 UI 实现
     * 「高亮光标下变量的全部出现」（VSCode 选中所有匹配项）。
     * - token 字符：字母/数字/`_`；非 token 字符处返回零宽区间 (offset,offset)；
     * - offset 夹紧到 `[0, text.length]`；结果恒满足 start<=end。
     */
    fun tokenRangeOf(text: String, offset: Int): Pair<Int, Int> {
        val len = text.length
        val off = clamp(offset, len)
        if (off >= len || !isTokenChar(text[off])) return off to off
        var s = off
        while (s > 0 && isTokenChar(text[s - 1])) s--
        var e = off + 1
        while (e < len && isTokenChar(text[e])) e++
        return s to e
    }

    /** 返回文本中与 [token]（非空标识符）完全匹配的全部出现区间，供多段选区一键高亮。 */
    fun occurrencesOf(text: String, token: String): List<Selection> {
        if (token.isEmpty()) return emptyList()
        if (token.any { !isTokenChar(it) }) return emptyList()
        val result = ArrayList<Selection>()
        var from = 0
        while (from <= text.length - token.length) {
            val idx = text.indexOf(token, from)
            if (idx < 0) break
            val beforeOk = idx == 0 || !isTokenChar(text[idx - 1])
            val afterIdx = idx + token.length
            val afterOk = afterIdx == text.length || !isTokenChar(text[afterIdx])
            if (beforeOk && afterOk) result.add(Selection(idx, afterIdx))
            from = idx + 1
        }
        return result
    }

    /** 0-based 行列映射，口径与 [CursorLayoutModel.lineColumnOf] 完全一致。 */
    fun lineColumnOf(text: String, offset: Int, tabWidth: Int = DEFAULT_TAB_WIDTH): CursorLayoutModel.LineColumn {
        val len = text.length
        val safe = clamp(offset, len)
        val tab = if (tabWidth <= 0) DEFAULT_TAB_WIDTH else tabWidth
        var line = 0
        var col = 0
        var i = 0
        while (i < safe) {
            val ch = text[i]
            if (ch == '\r') {
                if (i + 1 < len && text[i + 1] == '\n') {
                    if (i + 1 < safe) { line++; col = 0; i += 2 } else break
                } else { line++; col = 0; i++ }
                continue
            }
            if (ch == '\n') { line++; col = 0; i++; continue }
            col += if (ch == '\t') tab else 1
            i++
        }
        return CursorLayoutModel.LineColumn(line, col)
    }

    /** 计算第 [line] 行的可视行尾列（即换行符之前、按 tab 加权后的列）。 */
    private fun lineVisualEnd(text: String, line: Int, tabWidth: Int): Int {
        var current = 0
        var i = 0
        val len = text.length
        while (i < len) {
            if (current == line) break
            when (text[i]) {
                '\r' -> {
                    if (i + 1 < len && text[i + 1] == '\n') i += 2 else i++
                    current++
                }
                '\n' -> { i++; current++ }
                else -> i++
            }
        }
        var col = 0
        while (i < len && text[i] != '\n' && text[i] != '\r') {
            col += if (text[i] == '\t') tabWidth else 1
            i++
        }
        return col
    }

    private fun addRect(
        out: MutableList<HighlightRect>,
        line: Int,
        startCol: Int,
        endCol: Int,
        cw: Float,
        lh: Float
    ) {
        if (resultFull(out) || endCol <= startCol) return
        out.add(
            HighlightRect(
                line = line,
                startColumn = startCol,
                endColumn = endCol,
                xPx = startCol * cw,
                widthPx = (endCol - startCol) * cw,
                yPx = line * lh
            )
        )
    }

    private fun resultFull(out: List<HighlightRect>): Boolean = out.size >= cap

    private fun isTokenChar(ch: Char): Boolean =
        ch == '_' || ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9'

    private fun finiteOrZero(v: Float): Float = if (v.isNaN() || v < 0f) 0f else v

    private fun clamp(offset: Int, textLength: Int): Int {
        val upper = max(0, textLength)
        return when {
            offset < 0 -> 0
            offset > upper -> upper
            else -> offset
        }
    }

    companion object {
        const val DEFAULT_TAB_WIDTH: Int = 4
        const val DEFAULT_MAX_RECTS: Int = 256
    }
}
