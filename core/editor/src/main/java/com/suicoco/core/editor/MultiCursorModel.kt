package com.suicoco.core.editor

import kotlin.math.max
import kotlin.math.min

/**
 * 多光标编辑内核（纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测）。
 *
 * 对标 VSCode「多光标」：在一篇文本的若干偏移处维护一组光标，并对这组光标**同时**
 * 施加插入 / 退格 / 删除编辑。内核不持有文本本身（文本作为参数传入），只管理光标集合，
 * 每次编辑返回新的文本与新的光标位置，因而天然幂等、无副作用、可单测。
 *
 * 设计约束：
 *  - 光标集合始终升序、去重、夹紧到 `[0, textLength]`；
 *  - 容量有上限（[maxCursors]），超出后拒绝新增，避免长文档出现上千光标；
 *  - 所有公开方法失败安全：非法偏移夹紧、非法数量回退、空输入幂等，绝不抛异常；
 *  - 批量编辑用「一次遍历构建结果」算法，避免逐光标反复修改字符串导致偏移漂移与重叠删除；
 *  - 保持三条不变量（无 host root / 官方源 / 仅 arm64）：本类不涉及任何系统能力。
 */
class MultiCursorModel(private val maxCursors: Int = DEFAULT_MAX_CURSORS) {

    /** 一次编辑的结果：新文本 + 新光标集合（升序去重）+ 主光标位置。 */
    data class EditResult(
        val text: String,
        val cursors: List<Int>,
        val primary: Int
    )

    private val cursors = mutableSetOf<Int>()
    private var primary: Int = 0

    private val cap: Int
        get() = if (maxCursors < MIN_CURSORS) MIN_CURSORS else maxCursors

    /** 当前光标数量。 */
    fun cursorCount(): Int = cursors.size

    /** 当前光标位置（升序）。 */
    fun cursors(): List<Int> = cursors.sorted()

    /** 主光标位置（最后添加者；无光标时为 0）。 */
    fun primaryCursor(): Int = primary

    /** 设为单光标（替换全部），夹紧到 `[0, textLength]`。 */
    fun setSingle(offset: Int, textLength: Int) {
        val c = clamp(offset, textLength)
        cursors.clear()
        cursors.add(c)
        primary = c
    }

    /**
     * 添加一个光标。
     * @return 成功 true；位置重复、容量已满返回 false（不做部分添加）。
     */
    fun addCursor(offset: Int, textLength: Int): Boolean {
        val c = clamp(offset, textLength)
        if (cursors.contains(c)) return false
        if (cursors.size >= cap) return false
        cursors.add(c)
        primary = c
        return true
    }

    /** 移除指定偏移的光标；不存在返回 false。移除后集合永不为空。 */
    fun removeCursor(offset: Int): Boolean {
        if (!cursors.remove(offset)) return false
        if (cursors.isEmpty()) {
            cursors.add(0)
            primary = 0
        } else if (primary == offset) {
            primary = cursors.maxOrNull() ?: 0
        }
        return true
    }

    /** 清空为单光标（位置 0）。 */
    fun clearCursors() {
        cursors.clear()
        cursors.add(0)
        primary = 0
    }

    /**
     * 文本被外部（普通键盘输入）改变后，把所有光标夹紧到新长度并去重。
     * @return 夹紧后的光标集合（升序）。
     */
    fun reconcile(textLength: Int): List<Int> {
        val adjusted = cursors.map { clamp(it, textLength) }.toSortedSet()
        cursors.clear()
        cursors.addAll(adjusted)
        if (!cursors.contains(primary)) primary = cursors.maxOrNull() ?: 0
        return cursors.sorted()
    }

    /**
     * 在所有光标处同时插入 [s]。从后往前不改字符串：一次遍历构建结果，偏移稳定。
     * 光标停在新插入文本之后；空插入幂等（原样返回）。
     * 编辑后内核内部光标推进到结果位置，支持连续输入。
     */
    fun insertAtAll(text: String, s: String): EditResult {
        val sorted = cursors.sorted()
        if (sorted.isEmpty() || s.isEmpty()) return EditResult(text, sorted, primary)
        val len = text.length
        val safe = sorted.map { clamp(it, len) }
        val sb = StringBuilder(text.length + s.length * safe.size)
        val newCursors = IntArray(safe.size)
        var prev = 0
        for (i in safe.indices) {
            val c = safe[i]
            if (c > prev) sb.append(text, prev, c)
            sb.append(s)
            newCursors[i] = sb.length
            prev = c
        }
        if (prev < len) sb.append(text, prev, len)
        val np = newPrimary(sorted, safe, newCursors)
        return adopt(EditResult(sb.toString(), newCursors.toList(), np))
    }

    /**
     * 在所有光标处同时退格（删除光标前 [count] 个字符，Backspace 语义）。
     * 删除区按升序光标去重、防重叠；count 非法（<=0）时幂等。
     */
    fun deleteBeforeAll(text: String, count: Int = 1): EditResult {
        val sorted = cursors.sorted()
        if (sorted.isEmpty() || count <= 0) return EditResult(text, sorted, primary)
        val len = text.length
        val safe = sorted.map { clamp(it, len) }
        val sb = StringBuilder(len)
        val newCursors = IntArray(safe.size)
        var p = 0
        var deletedSoFar = 0
        for (i in safe.indices) {
            val c = safe[i]
            val left = if (i == 0) max(0, c - count) else max(safe[i - 1], c - count)
            if (left > p) sb.append(text, p, left)
            deletedSoFar += c - left
            newCursors[i] = c - deletedSoFar
            p = c
        }
        if (p < len) sb.append(text, p, len)
        val np = newPrimary(sorted, safe, newCursors)
        return adopt(EditResult(sb.toString(), newCursors.toList(), np))
    }

    /**
     * 在所有光标处同时删除（删除光标后 [count] 个字符，Delete 语义）。
     * 删除区按升序光标去重、防重叠；count 非法（<=0）时幂等。
     * 注意：删除发生在光标**右侧**，不导致光标自身左移——只有位于其左侧的其它
     * 光标删除才使它左移，因此 newCursors 用「累计到前一段为止的删除量」。
     */
    fun deleteAfterAll(text: String, count: Int = 1): EditResult {
        val sorted = cursors.sorted()
        if (sorted.isEmpty() || count <= 0) return EditResult(text, sorted, primary)
        val len = text.length
        val safe = sorted.map { clamp(it, len) }
        val sb = StringBuilder(len)
        val newCursors = IntArray(safe.size)
        var p = 0
        var deletedLeftOfCursor = 0
        for (i in safe.indices) {
            val c = safe[i]
            val limit = if (i + 1 < safe.size) safe[i + 1] else len
            val end = min(limit, c + count)
            if (c > p) sb.append(text, p, c)
            newCursors[i] = c - deletedLeftOfCursor
            deletedLeftOfCursor += end - c
            p = end
        }
        if (p < len) sb.append(text, p, len)
        val np = newPrimary(sorted, safe, newCursors)
        return adopt(EditResult(sb.toString(), newCursors.toList(), np))
    }

    /** 把一次编辑的结果光标集合写回内部状态，供连续编辑推进。 */
    private fun adopt(result: EditResult): EditResult {
        val normalized = result.cursors.map { max(0, it) }.toSortedSet()
        if (normalized.isEmpty()) normalized.add(0)
        cursors.clear()
        cursors.addAll(normalized)
        primary = if (normalized.contains(result.primary)) result.primary else normalized.maxOrNull() ?: 0
        return EditResult(result.text, normalized.sorted(), primary)
    }

    /**
     * 从 [from] 向下移动到下一行的同一列（列编辑放置光标用）。纯函数。
     * 无下一行时落在文本末尾。
     */
    fun columnDown(text: String, from: Int = primary): Int {
        val safe = clamp(from, text.length)
        val lineStart = if (safe == 0) 0 else text.lastIndexOf('\n', safe - 1) + 1
        val col = safe - lineStart
        val nextNl = text.indexOf('\n', safe)
        if (nextNl < 0) return text.length
        val destStart = nextNl + 1
        // 目标不得越过下一行末尾（下一个换行或文末），避免短行时串到更下方
        val nextLineEnd = text.indexOf('\n', destStart).let { if (it < 0) text.length else it }
        return clamp(destStart + col, nextLineEnd)
    }

    /**
     * 从 [from] 向上移动到上一行的同一列（列编辑放置光标用）。纯函数。
     * 无上一行时落在文本开头。
     */
    fun columnUp(text: String, from: Int = primary): Int {
        val safe = clamp(from, text.length)
        val lineStart = if (safe == 0) 0 else text.lastIndexOf('\n', safe - 1) + 1
        if (lineStart == 0) return 0
        val col = safe - lineStart
        val prevNl = text.lastIndexOf('\n', lineStart - 2)
        val prevStart = if (prevNl < 0) 0 else prevNl + 1
        val prevEnd = lineStart - 1
        return clamp(prevStart + col, prevEnd)
    }

    /** 主光标在结果中的新位置：按原偏移在新光标集合中定位。 */
    private fun newPrimary(original: List<Int>, safe: List<Int>, result: IntArray): Int {
        val idx = original.indexOf(primary)
        if (idx >= 0 && idx < result.size) return result[idx]
        return result.maxOrNull() ?: 0
    }

    private fun clamp(offset: Int, textLength: Int): Int {
        val upper = max(0, textLength)
        return when {
            offset < 0 -> 0
            offset > upper -> upper
            else -> offset
        }
    }

    companion object {
        const val DEFAULT_MAX_CURSORS: Int = 64
        const val MIN_CURSORS: Int = 1
    }
}
