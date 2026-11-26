package com.suicoco.core.editor

/**
 * 缩进指南线（Indent Guides）内核（v0.3.30）。
 *
 * 把 VSCode 的「缩进参考竖线」收敛为纯 Kotlin 计算：按每行前导空格 / tab 与 tab 宽度算出
 * 该行应绘制的 guide 列，跳过空行，并把相邻行同一列合并为连续竖线段，供 UI 用少量 Box 叠加。
 *
 * 设计约束（与 [AutoIndentModel] / [CursorLayoutModel] 等同口径）：
 *  - 视觉列按等宽字体计：空格 +1，tab 对齐到下一个制表位（下一个 tabWidth 的倍数）；
 *  - 只有前导空白构成缩进，遇到首个非空白字符即停止；空行 / 纯空白行不产生 guide；
 *  - 每行的 guide 列为 0, tabWidth, 2*tabWidth, … 且严格小于该行缩进宽度；
 *  - 行数 / 每行列数 / 总段数均有容量上限，空输入 / 非法 tabWidth 一律失败安全；
 *  - 纯函数幂等（同输入输出恒定），零 Android / Compose / 三方依赖，
 *    core 不依赖 feature 的架构约束不变。
 */
class IndentGuideModel {

    /** 单条指南锚点：0-based 行号 + 0-based 视觉列。 */
    data class Guide(val line: Int, val column: Int)

    /** 合并后的竖直段：[column] 列上从第 [startLine] 行到第 [endLine] 行（含）连续可见。 */
    data class GuideRun(val column: Int, val startLine: Int, val endLine: Int)

    companion object {
        const val DEFAULT_TAB_WIDTH = 4
        const val MAX_LINES = 4000
        const val MAX_GUIDES_PER_LINE = 64
        const val MAX_TOTAL_GUIDES = 12000
    }

    /** tabWidth 仅接受 1..8，其余回退默认值。 */
    private fun safeTabWidth(tabWidth: Int): Int =
        if (tabWidth in 1..8) tabWidth else DEFAULT_TAB_WIDTH

    /**
     * 按 CRLF / LF / 孤立 CR 拆行并保留空行；不使用正则 split，避免开销与空行丢失。
     * 空文本返回空列表；文末换行符产生一个末尾空行（与文本域行数口径一致）。
     */
    fun linesOf(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        var start = 0
        var i = 0
        while (i < text.length) {
            when (text[i]) {
                '\r' -> {
                    out.add(text.substring(start, i))
                    if (i + 1 < text.length && text[i + 1] == '\n') i++
                    start = i + 1
                }
                '\n' -> {
                    out.add(text.substring(start, i))
                    start = i + 1
                }
            }
            i++
        }
        out.add(text.substring(start))
        return out
    }

    /** 行的视觉缩进宽度：空格 +1，tab 对齐到下一个制表位，遇首个非空白即停；超容量夹紧。 */
    fun visualIndentOf(line: String, tabWidth: Int = DEFAULT_TAB_WIDTH): Int {
        val tw = safeTabWidth(tabWidth)
        var col = 0
        val ceiling = MAX_GUIDES_PER_LINE * tw
        for (c in line) {
            when (c) {
                ' ' -> col++
                '\t' -> col = (col / tw + 1) * tw
                else -> break
            }
            if (col >= ceiling) return ceiling
        }
        return col
    }

    /** 单行的 guide 列：0, tabWidth, 2*tabWidth, … 严格小于缩进宽度；空行 / 纯空白行 / 无缩进返回空。 */
    fun guideColumnsOf(line: String, tabWidth: Int = DEFAULT_TAB_WIDTH): List<Int> {
        val tw = safeTabWidth(tabWidth)
        // 纯空白行（含空行）视为空行跳过：无任何非空白字符时无 guide。
        if (line.isEmpty() || line.all { it == ' ' || it == '\t' }) return emptyList()
        val width = visualIndentOf(line, tw)
        if (width <= 0) return emptyList()
        val cols = ArrayList<Int>()
        var c = 0
        while (c < width && cols.size < MAX_GUIDES_PER_LINE) {
            cols.add(c)
            c += tw
        }
        return cols
    }

    /** 全文逐行指南锚点；空行与超容量行自动跳过，总段数封顶，失败安全。 */
    fun guidesOf(text: String, tabWidth: Int = DEFAULT_TAB_WIDTH): List<Guide> {
        val lines = linesOf(text)
        if (lines.isEmpty()) return emptyList()
        val out = ArrayList<Guide>()
        val n = minOf(lines.size, MAX_LINES)
        for (i in 0 until n) {
            val cols = guideColumnsOf(lines[i], tabWidth)
            for (c in cols) {
                out.add(Guide(i, c))
                if (out.size >= MAX_TOTAL_GUIDES) return out
            }
        }
        return out
    }

    /**
     * 把逐行锚点合并为连续竖直段：同一列在相邻行持续出现则并为一段，
     * 某行不再出现即闭合。结果按 (startLine, column) 稳定排序，保证幂等。
     */
    fun runsOf(text: String, tabWidth: Int = DEFAULT_TAB_WIDTH): List<GuideRun> {
        val lines = linesOf(text)
        if (lines.isEmpty()) return emptyList()
        val n = minOf(lines.size, MAX_LINES)
        // column -> 当前未闭合段的起始行
        val open = HashMap<Int, Int>()
        val out = ArrayList<GuideRun>()
        for (i in 0 until n) {
            val cols = guideColumnsOf(lines[i], tabWidth).toHashSet()
            // 本行不再出现的列：闭合到上一行
            val closed = open.keys.filter { it !in cols }.sorted()
            for (c in closed) {
                val s = open.remove(c) ?: continue
                out.add(GuideRun(c, s, i - 1))
                if (out.size >= MAX_TOTAL_GUIDES)
                    return out.sortedWith(compareBy<GuideRun> { it.startLine }.thenBy { it.column })
            }
            // 新出现的列：开启新段
            for (c in cols) if (!open.containsKey(c)) open[c] = i
        }
        // 文末闭合所有未闭合段
        for (e in open.entries.sortedBy { it.key }) {
            out.add(GuideRun(e.key, e.value, n - 1))
            if (out.size >= MAX_TOTAL_GUIDES) break
        }
        return out.sortedWith(compareBy<GuideRun> { it.startLine }.thenBy { it.column })
    }
}
