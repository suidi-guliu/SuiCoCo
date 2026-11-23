package com.suicoco.core.editor

import kotlin.math.max
import kotlin.math.min

/**
 * 逐光标 caret 定位内核（纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测）。
 *
 * 背景：[MultiCursorModel] 维护一组光标偏移，但偏移是「文本下标」，UI 无法直接看到光标落在
 * 哪一行哪一列。本内核把文本偏移映射为 (行, 列) 与像素坐标，供编辑器在文本域叠加多个 caret
 * 竖线——即「逐光标定位渲染」的纯逻辑部分。文本本身仍作为参数传入，内核无隐藏状态，天然幂等、可单测。
 *
 * 设计约束：
 *  - 行/列均 0-based；列按 UTF-16 code unit 计（与 Kotlin String index 一致），tab 按 [tabWidth] 加权；
 *  - CRLF（`\r\n`）视为单个换行，offset 落在 `\r` 与 `\n` 之间时归到该行行尾；孤立 `\r` 视为换行；
 *  - 偏移统一夹紧到 `[0, text.length]`，去重升序；caret 数量有上限 [maxCarets]，避免长文档画爆；
 *  - 所有公开方法失败安全：非法 tabWidth / 像素尺寸回退，空输入幂等，绝不抛异常；
 *  - 保持三条不变量（无 host root / 官方源 / 仅 arm64）：本类不涉及任何系统能力。
 */
class CursorLayoutModel(private val maxCarets: Int = DEFAULT_MAX_CARETS) {

    /** 0-based 行列。 */
    data class LineColumn(val line: Int, val column: Int)

    /** 一个可渲染的 caret：源偏移 + 行列 + 像素坐标（不含文本区 padding，由 UI 叠加）。 */
    data class CaretPoint(
        val offset: Int,
        val line: Int,
        val column: Int,
        val xPx: Float,
        val yPx: Float
    )

    private val cap: Int
        get() = if (maxCarets < 1) DEFAULT_MAX_CARETS else maxCarets

    /**
     * 把文本偏移 [offset] 映射为 (行, 列)。
     * - offset 夹紧到 `[0, text.length]`；
     * - `tabWidth<=0` 回退默认 4；
     * - CRLF 两字符算一次换行；offset 停在 `\r` 与 `\n` 之间时视为本行行尾。
     */
    fun lineColumnOf(text: String, offset: Int, tabWidth: Int = DEFAULT_TAB_WIDTH): LineColumn {
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
                    // i+1 已被 offset 越过才真正换行；否则 offset 停在 CRLF 中间 → 本行行尾
                    if (i + 1 < safe) {
                        line++
                        col = 0
                        i += 2
                    } else {
                        break
                    }
                } else {
                    // 孤立 \r 视为换行
                    line++
                    col = 0
                    i++
                }
                continue
            }
            if (ch == '\n') {
                line++
                col = 0
                i++
                continue
            }
            col += if (ch == '\t') tab else 1
            i++
        }
        return LineColumn(line, col)
    }

    /**
     * 把一组偏移映射为像素坐标 caret 点。
     * - 偏移夹紧、去重、升序；超过 [maxCarets] 取最小的 cap 个；
     * - `xPx = column * charWidthPx`，`yPx = line * lineHeightPx`（文本区 padding 由 UI 叠加）；
     * - 非法像素尺寸（NaN / 负数）回退为 0；
     * - 空输入返回空列表。
     */
    fun carets(
        text: String,
        offsets: List<Int>,
        lineHeightPx: Float,
        charWidthPx: Float,
        tabWidth: Int = DEFAULT_TAB_WIDTH
    ): List<CaretPoint> {
        if (offsets.isEmpty()) return emptyList()
        val len = text.length
        val lh = finiteOrZero(lineHeightPx)
        val cw = finiteOrZero(charWidthPx)
        val unique = offsets.map { clamp(it, len) }.toSortedSet()
        val result = ArrayList<CaretPoint>(min(unique.size, cap))
        var count = 0
        for (off in unique) {
            if (count >= cap) break
            val lc = lineColumnOf(text, off, tabWidth)
            result.add(CaretPoint(off, lc.line, lc.column, lc.column * cw, lc.line * lh))
            count++
        }
        return result
    }

    private fun finiteOrZero(v: Float): Float =
        if (v.isNaN() || v < 0f) 0f else v

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
        const val DEFAULT_MAX_CARETS: Int = 64
    }
}
