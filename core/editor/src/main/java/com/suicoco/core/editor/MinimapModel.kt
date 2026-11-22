package com.suicoco.core.editor

import kotlin.math.max
import kotlin.math.min

/**
 * 代码缩略图（minimap）内核：纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测。
 *
 * 把整篇文档压成一列「墨迹条」：每行按其缩进、有效长度、是否注释/空行映射为一个密度等级，
 * UI 只需把每个 [MinimapLine] 画成一条细横杠即可得到 VSCode 风格的代码缩略图。
 * 同时提供视口高亮矩形计算 [viewportRect] 与点击位置到行号的映射 [lineAt]，供滚动定位。
 *
 * 设计约束（与 [UndoTree] 一致）：
 *  - 所有公开方法失败安全：非法参数返回安全值 / 空结果，绝不抛异常；
 *  - 容量有上限 [Config.maxLines]，超大文件只解析前 N 行并置 [MinimapLayout.truncated]，
 *    避免长文件渲染卡顿与内存放大；
 *  - 纯函数式：相同输入必得相同输出（不依赖时间/随机），便于 UI 幂等重组；
 *  - 保持三条不变量（无 host root / 官方源 / 仅 arm64）：本类不涉及任何系统能力。
 */
class MinimapModel(private val config: Config = Config()) {

    /** 行的视觉类别，供 UI 选色。 */
    enum class Kind { BLANK, COMMENT, CODE }

    /** 单行缩略信息。 */
    data class MinimapLine(
        val index: Int,        // 0-based 行号
        val indent: Int,       // 前导空白列数（tab 记 [Config.tabWidth]）
        val length: Int,       // 去前导空白后的有效字符数
        val kind: Kind,
        val density: Int       // 0..4 墨迹密度等级
    )

    /** 整篇文档的缩略结果。 */
    data class MinimapLayout(
        val lines: List<MinimapLine>,
        val lineCount: Int,        // 实际解析的行数（= lines.size）
        val sourceLineCount: Int,  // 源文件总行数
        val truncated: Boolean     // 是否因 [Config.maxLines] 截断
    ) {
        fun isEmpty(): Boolean = lines.isEmpty()
    }

    /** 视口高亮矩形（以「行」为单位，UI 自行乘行高换算像素）。 */
    data class ViewportRect(
        val topLine: Float,     // 视口上边界（可为小数，对应首行内偏移）
        val heightLines: Float  // 视口覆盖的行数
    )

    /** 可调配置。所有阈值都有下限保护，非法值自动回退到默认。 */
    data class Config(
        val maxLines: Int = DEFAULT_MAX_LINES,
        val tabWidth: Int = DEFAULT_TAB_WIDTH,
        val rowPx: Float = DEFAULT_ROW_PX
    )

    /** 解析文档为缩略布局。空串视为一行空文档。 */
    fun build(content: String): MinimapLayout {
        val srcLines = splitLines(content)
        // 非法阈值回退默认（与 Config 文档一致），而非夹紧到 1
        val limit = if (config.maxLines < 1) DEFAULT_MAX_LINES else config.maxLines
        val take = min(srcLines.size, limit)
        val out = ArrayList<MinimapLine>(take)
        var i = 0
        while (i < take) {
            out.add(analyzeLine(i, srcLines[i]))
            i++
        }
        return MinimapLayout(
            lines = out,
            lineCount = out.size,
            sourceLineCount = srcLines.size,
            truncated = srcLines.size > limit
        )
    }

    /**
     * 视口高亮矩形：给定首个可见行与可见行数，返回在缩略列中的相对位置。
     * 非法输入（首行 <0 / 可见数 <=0）被夹紧到合法范围；布局为空时返回全 0。
     */
    fun viewportRect(layout: MinimapLayout, firstVisibleLine: Int, visibleCount: Int): ViewportRect {
        val total = layout.lineCount
        if (total <= 0) return ViewportRect(0f, 0f)
        val first = firstVisibleLine.coerceIn(0, total - 1)
        val visible = min(max(1, visibleCount), total - first)
        return ViewportRect(first.toFloat(), visible.toFloat())
    }

    /**
     * 点击位置（以「行」为单位的浮点 y）映射到源文档行号。
     * 结果夹紧到 [0, sourceLineCount-1]；布局为空返回 0；若解析被截断，按比例换算回源行号。
     */
    fun lineAt(layout: MinimapLayout, yInLines: Float): Int {
        if (layout.lineCount <= 0) return 0
        val clamped = if (yInLines.isNaN()) 0f else max(0f, yInLines)
        val local = min(clamped.toInt(), layout.lineCount - 1)
        return if (!layout.truncated || layout.sourceLineCount <= 0) {
            local
        } else {
            // 截断时按解析窗口占源文档的比例放大，落点仍夹紧到源范围
            val scaled = (clamped / layout.lineCount * layout.sourceLineCount).toInt()
            min(max(scaled, 0), layout.sourceLineCount - 1)
        }
    }

    /**
     * 滚动锚点：把「点击映射出的源行号」换算为编辑器应滚动到的首个可见行（0-based）。
     * 目标是让 [targetLine] 尽量落在视口中央（上移半个视口），再夹紧到
     * `[0, sourceLineCount - visibleLines]`。用于 minimap 点击/拖拽跳转接线。
     * 失败安全：空布局返回 0；[targetLine] / [visibleLines] 非法时夹紧；纯函数幂等。
     */
    fun scrollAnchorLine(layout: MinimapLayout, targetLine: Int, visibleLines: Int): Int {
        val total = layout.sourceLineCount
        if (total <= 0) return 0
        val visible = min(max(1, visibleLines), total)
        val t = if (targetLine < 0) 0 else min(targetLine, total - 1)
        val anchor = t - visible / 2
        return anchor.coerceIn(0, total - visible)
    }

    /** 每次滚动的像素行距换算：行高像素 → 缩略行单位。非法 rowPx 回退默认。 */
    fun pixelsToLines(pixels: Float): Float {
        val px = if (config.rowPx > 0f && !config.rowPx.isNaN()) config.rowPx else DEFAULT_ROW_PX
        return if (pixels <= 0f || pixels.isNaN()) 0f else pixels / px
    }

    /** 单行解析：缩进 → 类别 → 密度分级。 */
    private fun analyzeLine(index: Int, raw: String): MinimapLine {
        var indent = 0
        var p = 0
        val tw = max(1, config.tabWidth)
        while (p < raw.length && (raw[p] == ' ' || raw[p] == '\t')) {
            indent += if (raw[p] == '\t') tw else 1
            p++
        }
        val body = if (p <= raw.length) raw.substring(p) else ""
        val trimmed = body.trimEnd()
        val kind = when {
            trimmed.isEmpty() -> Kind.BLANK
            isComment(trimmed) -> Kind.COMMENT
            else -> Kind.CODE
        }
        val density = when (kind) {
            Kind.BLANK -> 0
            Kind.COMMENT -> 1
            Kind.CODE -> codeDensity(trimmed.length, indent)
        }
        return MinimapLine(index, indent, trimmed.length, kind, density)
    }

    /** 代码密度：以有效长度为主、缩进深度为辅，最终夹紧到 1..4。 */
    private fun codeDensity(len: Int, indent: Int): Int {
        var d = when {
            len <= 0 -> 1
            len <= 10 -> 1
            len <= 25 -> 2
            len <= 50 -> 3
            else -> 4
        }
        if (indent >= 12) d += 1
        return min(max(d, 1), 4)
    }

    /** 常见注释前缀（Kotlin/Java/C/C++/Python/Shell）。 */
    private fun isComment(s: String): Boolean =
        s.startsWith("//") || s.startsWith("/*") || s.startsWith("*") ||
            s.startsWith("#") || s.startsWith("<!--")

    /** 与 String.split 等价但失败安全：保留行内容，兼容 \r\n 与 \n。 */
    private fun splitLines(content: String): List<String> {
        if (content.isEmpty()) return listOf("")
        val normalized = content.replace("\r\n", "\n")
        return normalized.split('\n')
    }

    companion object {
        const val DEFAULT_MAX_LINES: Int = 2000
        const val DEFAULT_TAB_WIDTH: Int = 4
        const val DEFAULT_ROW_PX: Float = 2f
        const val MAX_DENSITY: Int = 4
    }
}
