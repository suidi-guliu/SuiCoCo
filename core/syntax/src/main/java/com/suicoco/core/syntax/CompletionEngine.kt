package com.suicoco.core.syntax

/**
 * 智能补全内核（纯 Kotlin 标准库，无 Android / Compose 依赖，可独立编译单测）。
 *
 * 替代 app 层 MainViewModel 原先的 `label.startsWith(prefix)` 纯前缀过滤：
 * 1. 三级模糊打分（精确 > 前缀包含 > 子序列 fuzzy），大小写不敏感；
 * 2. 候选类型加权（keyword/snippet/function/word），短标签优先；
 * 3. 从当前文档已输入的标识符动态提取「文档词」作为补全来源；
 * 4. 光标 token 提取与补全插入区间计算（修复 app 层把 token 长度误当列位置的 bug）。
 *
 * 保持三条不变量：无 host root / apt 官方源 / 仅 arm64；本类不涉及任何系统能力。
 */
object CompletionEngine {

    /** 候选补全项（core 层数据，结构与 app 层 CompletionItem 对齐，便于映射） */
    data class Candidate(
        val label: String,
        val kind: String,
        val detail: String = "",
        val insertText: String = label
    )

    /** 带分数的候选（内部排序用） */
    private data class Scored(val candidate: Candidate, val score: Int, val order: Int)

    /** 类型权重：snippet / function 优先于 keyword，文档词最低 */
    private fun kindWeight(kind: String): Int = when (kind.lowercase()) {
        "snippet" -> 40
        "function" -> 30
        "keyword" -> 20
        "word" -> 0
        else -> 10
    }

    /**
     * 计算 query 与候选标签的匹配分。返回 null 表示不匹配。
     * 打分体系（越大越优先）：
     *  - 精确相等：100000
     *  - 前缀匹配：10000 + 前缀长度优势 - 标签长度惩罚
     *  - 子串包含：5000 + 位置优势 - 标签长度惩罚
     *  - 子序列匹配（fuzzy，如 pm -> print）：2000 + 紧凑度 - 标签长度惩罚
     * 叠加类型权重，再叠加一个微小的 order 保证稳定排序。
     */
    fun matchScore(query: String, candidate: Candidate): Int? {
        if (query.isEmpty()) return null
        val q = query.lowercase()
        val label = candidate.label.lowercase()
        if (label.isEmpty()) return null
        val kw = kindWeight(candidate.kind)
        val lenPenalty = candidate.label.length.coerceAtMost(60)
        return when {
            label == q -> 100000 + kw
            label.startsWith(q) -> 10000 + (q.length * 10) - lenPenalty + kw
            label.contains(q) -> {
                val pos = label.indexOf(q)
                5000 + ((label.length - pos) * 4) - lenPenalty + kw
            }
            isSubsequence(q, label) -> 2000 + (q.length * 8) - lenPenalty + kw
            else -> null
        }
    }

    /** 判断 pattern 是否为 text 的子序列（字符按顺序出现即可，允许间隔） */
    fun isSubsequence(pattern: String, text: String): Boolean {
        if (pattern.isEmpty()) return true
        if (pattern.length > text.length) return false
        var i = 0
        var j = 0
        while (i < pattern.length && j < text.length) {
            if (pattern[i] == text[j]) i++
            j++
        }
        return i == pattern.length
    }

    /**
     * 从当前文档提取「文档词」——已输入过的标识符（长度 >= 3，跳过纯数字），
     * 按出现频次降序、字母序升序排列，作为动态补全来源（对标 VSCode word-based suggestions）。
     */
    fun documentWords(text: String, minLength: Int = 3, limit: Int = 200): List<Candidate> {
        val counts = LinkedHashMap<String, Int>()
        val sb = StringBuilder()
        for (ch in text) {
            if (ch.isLetterOrDigit() || ch == '_') {
                sb.append(ch)
            } else {
                recordWord(sb, counts, minLength)
                sb.setLength(0)
            }
        }
        recordWord(sb, counts, minLength)
        return counts.entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .take(limit)
            .map { Candidate(label = it.key, kind = "word", detail = "文档词") }
    }

    private fun recordWord(sb: StringBuilder, counts: MutableMap<String, Int>, minLength: Int) {
        if (sb.isEmpty()) return
        val w = sb.toString()
        if (w.length >= minLength && w.any { it.isLetter() }) {
            counts[w] = (counts[w] ?: 0) + 1
        }
    }

    /**
     * 生成补全建议：对静态候选池 + 文档词逐一打分，过滤不匹配项，按分数降序取前 [limit] 个。
     * 大小写不敏感，结果稳定（同分按候选池输入顺序）。
     */
    fun suggest(
        query: String,
        pool: List<Candidate>,
        documentText: String = "",
        limit: Int = 8
    ): List<Candidate> {
        if (query.isEmpty()) return emptyList()
        val all = pool + documentWords(documentText)
        val scored = ArrayList<Scored>(all.size)
        var order = 0
        for (c in all) {
            val s = matchScore(query, c)
            if (s != null) scored.add(Scored(c, s, order))
            order++
        }
        return scored
            .sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.order })
            .take(limit.coerceAtLeast(0))
            .map { it.candidate }
    }

    /**
     * 提取光标所在行的当前 token（最后一个连续标识符片段），返回 (startIndex, token)。
     * 用于计算补全插入区间，替代原先「把 token 长度当列位置」的错误做法。
     */
    fun currentToken(line: String): Pair<Int, String> {
        var start = line.length
        while (start > 0 && isIdentChar(line[start - 1])) start--
        return start to line.substring(start)
    }

    private fun isIdentChar(ch: Char): Boolean = ch.isLetterOrDigit() || ch == '_'

    /**
     * 从给定的 token 起始列向后扫描，返回 token 结束列（不含）。
     * 供补全接受时计算替换区间 [startCol, tokenEnd)，修复原先误把 token.length 当列位置的 bug。
     * 即使光标后还有其它字符（如 "pr |;"），也能正确只替换 token 本身。
     */
    fun tokenEndFrom(line: String, startCol: Int): Int {
        var end = startCol.coerceIn(0, line.length)
        while (end < line.length && isIdentChar(line[end])) end++
        return end
    }

    /**
     * 计算把 token 替换为 insertText 后的新行字符串。
     * @param line 当前整行原文
     * @param insertText 要插入的补全文本
     * @param caretColumn 光标所在列（缺省为行尾）；补全插入总是发生在光标当前 token 处
     */
    fun applyCompletion(line: String, insertText: String, caretColumn: Int = line.length): String {
        val caret = caretColumn.coerceIn(0, line.length)
        var start = caret
        while (start > 0 && isIdentChar(line[start - 1])) start--
        return line.substring(0, start) + insertText + line.substring(caret)
    }
}
