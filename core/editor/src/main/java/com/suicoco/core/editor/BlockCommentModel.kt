package com.suicoco.core.editor

/**
 * 块注释切换内核（Toggle Block Comment，纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测）。
 *
 * [CommentModel]（v0.3.22）仅支持行注释 `//` / `#` / `--`；本内核补齐 VSCode 常用的块注释切换
 * （`/* */`、`<!-- -->`、Python `""" """`）：有选区则包裹选区，无选区则包裹当前行；若内容已被
 * 同一对符号包裹则解除。全部逻辑为纯字符串处理，UTF-16 code unit 偏移，CRLF/LF/CR 原样保留。
 *
 * 口径与约束：
 *  - [tokensFor] 按文件扩展名推断符号对：C/Kotlin/Java/JS/Go/Rust/CSS/SQL → `/* */`，
 *    HTML/XML/Vue/Svelte/Markdown → `<!-- -->`，Python → `""" """`；Shell/YAML 等无块注释语言返回 null；
 *  - 包裹：在内容前后插入 open/close，选区收敛到内层内容（便于继续输入）；
 *  - 解除：仅当目标区域「跳过首尾空白后」严格以 open 开头、以 close 结尾且两者之间还有内容时成立，
 *    删除成对符号、保留首尾空白与内部文本；
 *  - 折叠光标（start==end）扩展到当前行（不含行尾符）；空行不处理；
 *  - 越界偏移夹紧、空/非法符号对、超长文本失败安全，纯函数；重复切换（配合返回选区）回到原文，幂等。
 */
class BlockCommentModel {

    /** 符号对。[open] 为起始符、[close] 为结束符。 */
    data class Tokens(val open: String, val close: String)

    /** 切换结果。[changed] 表示文本是否实际变化。 */
    data class Result(
        val text: String,
        val selectionStart: Int,
        val selectionEnd: Int,
        val changed: Boolean
    )

    /** 按文件名推断块注释符号对；不支持块注释的语言（Shell/YAML/Properties）返回 null。 */
    fun tokensFor(fileName: String): Tokens? {
        val lower = fileName.lowercase()
        val dot = lower.lastIndexOf('.')
        val ext = if (dot >= 0) lower.substring(dot + 1) else lower
        return when (ext) {
            "py", "pyw" -> Tokens("\"\"\"", "\"\"\"")
            "html", "htm", "xml", "xhtml", "svg", "vue", "svelte", "jsp", "md", "markdown" ->
                Tokens("<!--", "-->")
            "c", "h", "cc", "cpp", "cxx", "hpp", "java", "kt", "kts", "js", "jsx", "ts", "tsx",
            "go", "rs", "swift", "scala", "sc", "css", "scss", "less", "sql", "php", "dart" ->
                Tokens("/*", "*/")
            else -> null
        }
    }

    /** 折叠光标所在行的内容区间（不含行尾符），越界夹紧。 */
    fun lineRangeOf(text: String, offset: Int): Pair<Int, Int> {
        if (text.isEmpty()) return 0 to 0
        val off = offset.coerceIn(0, text.length)
        var start = off
        while (start > 0 && text[start - 1] != '\n' && text[start - 1] != '\r') start--
        var end = off
        while (end < text.length && text[end] != '\n' && text[end] != '\r') end++
        return start to end
    }

    /** 切换 [selectionStart]..[selectionEnd]（UTF-16，半开区间）覆盖的块注释状态。 */
    fun toggle(
        text: String,
        selectionStart: Int,
        selectionEnd: Int,
        tokens: Tokens?
    ): Result {
        if (tokens == null || tokens.open.isEmpty() || tokens.close.isEmpty() || text.isEmpty() ||
            text.length > MAX_TEXT_CHARS
        ) {
            return Result(text, selectionStart.coerceIn(0, text.length), selectionEnd.coerceIn(0, text.length), false)
        }
        val s0 = selectionStart.coerceIn(0, text.length)
        val e0 = selectionEnd.coerceIn(s0, text.length)

        // 折叠光标 → 扩展到当前行；空行不处理。
        val (s, e) = if (s0 == e0) {
            val (ls, le) = lineRangeOf(text, s0)
            if (ls == le) return Result(text, s0, e0, false)
            ls to le
        } else s0 to e0
        if (e - s < 0) return Result(text, s0, e0, false)

        // 包裹后选区收敛到内层内容；再次 toggle 时符号对在选区之外，需向外扩展一层再解除。
        var s2 = s
        var e2 = e
        if (s >= tokens.open.length && e + tokens.close.length <= text.length &&
            text.startsWith(tokens.open, s - tokens.open.length) &&
            text.startsWith(tokens.close, e)
        ) {
            s2 = s - tokens.open.length
            e2 = e + tokens.close.length
        }
        val region = text.substring(s2, e2)
        val toggled = toggleRegion(region, tokens) ?: return Result(text, s0, e0, false)

        val newText = text.substring(0, s2) + toggled.region + text.substring(e2)
        val newStart = (s2 + toggled.start).coerceIn(0, newText.length)
        val newEnd = (s2 + toggled.end).coerceIn(newStart, newText.length)
        return Result(newText, newStart, newEnd, true)
    }

    private data class RegionResult(val region: String, val start: Int, val end: Int)

    /** 对单个区域做包裹/解除，返回新区域与相对选区。失败返回 null。 */
    private fun toggleRegion(region: String, tokens: Tokens): RegionResult? {
        val open = tokens.open
        val close = tokens.close
        if (region.length > MAX_REGION_CHARS) return null

        // 先尝试解除：跳过首尾空白后严格以 open 开头、以 close 结尾。
        var lead = 0
        while (lead < region.length && region[lead].isWhitespace()) lead++
        var trail = 0
        while (trail < region.length - lead && region[region.length - 1 - trail].isWhitespace()) trail++
        val innerEnd = region.length - trail
        if (innerEnd - lead >= open.length + close.length &&
            region.startsWith(open, lead) &&
            region.startsWith(close, innerEnd - close.length)
        ) {
            val contentStart = lead + open.length
            val contentEnd = innerEnd - close.length
            val inner = region.substring(contentStart, contentEnd)
            val newRegion = region.substring(0, lead) + inner + region.substring(innerEnd)
            return RegionResult(newRegion, lead, lead + inner.length)
        }

        // 否则包裹：跳过首尾的空格/tab 后插入 open/close，保留首尾缩进在符号对外，选区收敛到内层内容。
        if (region.length + open.length + close.length > MAX_REGION_CHARS) return null
        var wrapLead = 0
        while (wrapLead < region.length && (region[wrapLead] == ' ' || region[wrapLead] == '\t')) wrapLead++
        var wrapTrail = 0
        while (wrapTrail < region.length - wrapLead &&
            (region[region.length - 1 - wrapTrail] == ' ' || region[region.length - 1 - wrapTrail] == '\t')
        ) wrapTrail++
        val inner = region.substring(wrapLead, region.length - wrapTrail)
        val newRegion = region.substring(0, wrapLead) + open + inner + close + region.substring(region.length - wrapTrail)
        return RegionResult(newRegion, wrapLead + open.length, wrapLead + open.length + inner.length)
    }

    companion object {
        const val MAX_TEXT_CHARS: Int = 2_000_000
        const val MAX_REGION_CHARS: Int = 200_000
    }
}
