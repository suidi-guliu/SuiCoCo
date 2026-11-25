package com.suicoco.core.editor

/**
 * 代码大纲（Symbol Outline）内核（纯 Kotlin 标准库，无 Android / Compose / 三方依赖，可独立编译单测）。
 *
 * 背景：[FoldingModel] / [BracketMatchModel] / [CommentModel] 已覆盖折叠、配对、注释切换，但编辑器
 * 仍缺 VSCode 侧栏最高频的「代码大纲 / 符号导航」能力。本内核把「从源码扫出声明符号（类/接口/
 * 对象/枚举/结构体/函数/方法/命名空间/箭头函数变量），输出符号名、种类、行号、偏移」拆成可单测的
 * 纯逻辑：词法扫描跳过字符串/字符字面量与 `//`、`/* */` 注释，只识别代码区里的声明关键字，
 * 保证注释/字符串中的 `class`、`fun` 等词不会被误报为符号。
 *
 * 口径与约束：
 *  - 行号、偏移均为 0-based UTF-16 code unit（与 Kotlin String index 一致）；行号含首尾；
 *  - 声明关键字：Kotlin/Java/C/JS 的 `class`/`interface`/`object`/`enum`/`struct`/`record`，
 *    Kotlin/Java 的 `fun`，Python 的 `def`，JS/C 的 `function`/`func`，Go 的 `func`（含方法接收者），
 *    C#/Kotlin 的 `namespace`，以及 JS 的 `const`/`let`/`var` 箭头函数；
 *  - 关键字前只允许「修饰词 / `@注解`」，避免把 `val x = Foo::class.java` 之类的普通表达式误报；
 *  - 结果按源文件出现顺序（行号升序）返回，同一起始行最多一个符号；
 *  - 容量上限 [maxSymbols]，超大文件只取前 N 个符号，避免长文档构造过大列表；
 *  - 全部公开方法失败安全：空文本 / 越界 / 非法配置均安全回退，绝不抛异常；纯函数幂等；
 *  - 保持三条不变量（无 host root / 官方源 / 仅 arm64）：本类不涉及任何系统能力。
 */
class SymbolOutlineModel(private val maxSymbols: Int = DEFAULT_MAX_SYMBOLS) {

    /** 符号种类。 */
    enum class Kind {
        CLASS, INTERFACE, OBJECT, ENUM, STRUCT, FUNCTION, METHOD, VARIABLE, NAMESPACE
    }

    /**
     * 一个声明符号。
     * [line] 为 0-based 行号，[offset] 为符号名在原文中的 0-based 字符偏移，
     * [indent] 为前导空白字符数（供 UI 按层级缩进显示）。
     */
    data class Symbol(
        val name: String,
        val kind: Kind,
        val line: Int,
        val offset: Int,
        val indent: Int
    )

    /** 词法扫描的一个 token，[start] 为其在（已遮蔽注释/字符串的）行内的起始下标。 */
    private data class Tok(val text: String, val start: Int)

    private enum class LexState { CODE, LINE_COMMENT, BLOCK_COMMENT, STRING, CHAR }

    /** 单行遮蔽结果：[text] 与原行等长（注释/字符串内容为空格），[state]/[quote] 供下一行续接。 */
    private data class Masked(val text: String, val state: LexState, val quote: Char)

    /**
     * 扫描文档，按出现顺序返回所有声明符号；结果不含注释/字符串内的伪声明。
     * 失败安全：空文本返回空列表；配置非法回退默认容量。
     */
    fun symbolsOf(content: String): List<Symbol> {
        if (content.isEmpty()) return emptyList()
        val limit = if (maxSymbols < 1) DEFAULT_MAX_SYMBOLS else maxSymbols
        val lines = splitLines(content)
        val starts = lineStartOffsets(content)
        val out = ArrayList<Symbol>()
        var state = LexState.CODE
        var quote = NO_QUOTE
        for (li in lines.indices) {
            val line = lines[li]
            val masked = maskLine(line, state, quote)
            state = masked.state
            quote = masked.quote
            if (masked.text.isBlank()) continue
            val recognized = recognize(tokenize(masked.text), li, starts[li]) ?: continue
            out.add(recognized)
            if (out.size >= limit) break
        }
        return out
    }

    /** 取起始于 [line] 行的符号（供大纲与光标行联动高亮）；无则 null。 */
    fun symbolAt(symbols: List<Symbol>, line: Int): Symbol? =
        symbols.firstOrNull { it.line == line }

    /** 按种类过滤（供 UI 分类展示），保持原有顺序。 */
    fun ofKind(symbols: List<Symbol>, kind: Kind): List<Symbol> =
        symbols.filter { it.kind == kind }

    // ---------- 内部实现 ----------

    /**
     * 把注释与字符串字面量遮蔽为等长空格，只保留代码区字符，使后续 token 的下标与原文对齐。
     * 行尾未闭合的行注释/字符串/字符在换行处容错恢复为代码态，块注释跨行保持。
     */
    private fun maskLine(line: String, initialState: LexState, initialQuote: Char): Masked {
        val sb = StringBuilder(line.length)
        var state = initialState
        var quote = initialQuote
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when (state) {
                LexState.CODE -> when {
                    c == '/' && i + 1 < line.length && line[i + 1] == '/' -> {
                        sb.append("  ")
                        state = LexState.LINE_COMMENT
                        i += 2
                    }
                    c == '/' && i + 1 < line.length && line[i + 1] == '*' -> {
                        sb.append("  ")
                        state = LexState.BLOCK_COMMENT
                        i += 2
                    }
                    c == '"' || c == '\'' || c == '`' -> {
                        sb.append(' ')
                        quote = c
                        state = if (c == '\'') LexState.CHAR else LexState.STRING
                        i++
                    }
                    else -> {
                        sb.append(c)
                        i++
                    }
                }
                LexState.LINE_COMMENT -> {
                    sb.append(' ')
                    i++
                }
                LexState.BLOCK_COMMENT -> {
                    if (c == '*' && i + 1 < line.length && line[i + 1] == '/') {
                        sb.append("  ")
                        state = LexState.CODE
                        i += 2
                    } else {
                        sb.append(' ')
                        i++
                    }
                }
                LexState.STRING, LexState.CHAR -> {
                    sb.append(' ')
                    if (c == '\\') {
                        // 转义：反斜杠与其后一字符都并入遮蔽区，保持长度对齐。
                        if (i + 1 < line.length) sb.append(' ')
                        i += if (i + 1 < line.length) 2 else 1
                    } else if (c == quote) {
                        state = LexState.CODE
                        quote = NO_QUOTE
                        i++
                    } else {
                        i++
                    }
                }
            }
        }
        // 行注释止于行尾；未闭合的字符串/字符在换行处容错恢复，避免后的内容被整段遮蔽。
        val endState = if (state == LexState.LINE_COMMENT || state == LexState.STRING || state == LexState.CHAR)
            LexState.CODE else state
        return Masked(sb.toString(), endState, if (endState == LexState.CODE) NO_QUOTE else quote)
    }

    /** 极简词法：标识符成串，空白跳过，其余字符（含 `<`/`>`/`(`/`)`/`:`/`{`）各成单字符 token。 */
    private fun tokenize(line: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < line.length) {
            val c = line[i]
            if (c.isWhitespace()) {
                i++ // 空白不作为 token，避免干扰前缀/泛型/接收者判定；缩进仍由首个 token 的 start 反映
            } else if (isIdentStart(c)) {
                val s = i
                i++
                while (i < line.length && isIdentPart(line[i])) i++
                out.add(Tok(line.substring(s, i), s))
            } else {
                out.add(Tok(c.toString(), i))
                i++
            }
        }
        return out
    }

    /** 从一行 token 中识别首个声明；返回 null 表示该行不是声明或为误报。 */
    private fun recognize(toks: List<Tok>, line: Int, lineStart: Int): Symbol? {
        if (toks.isEmpty()) return null
        var k = -1
        for (idx in toks.indices) {
            if (toks[idx].text in DECL_KEYWORDS) {
                k = idx
                break
            }
        }
        if (k < 0) return null
        val kw = toks[k].text
        var j = k + 1
        val kind: Kind
        // Go `type Name struct{}` / `type Name interface{}`：类型名在关键字之前，直接取 type 后的标识符。
        if ((kw == "struct" || kw == "interface") && k >= 2 && toks[k - 2].text == "type") {
            val nameTok = toks[k - 1]
            if (!isIdentifier(nameTok.text) || nameTok.text in RESERVED) return null
            val gk = if (kw == "struct") Kind.STRUCT else Kind.INTERFACE
            return Symbol(nameTok.text, gk, line, lineStart + nameTok.start, indentOf(toks))
        }
        if (!prefixOk(toks, k)) return null
        when (kw) {
            "class" -> kind = Kind.CLASS
            "struct", "record" -> kind = Kind.STRUCT
            "interface" -> kind = Kind.INTERFACE
            "object" -> kind = Kind.OBJECT
            "enum" -> {
                kind = Kind.ENUM
                if (j < toks.size && toks[j].text == "class") j++ // Kotlin `enum class`
            }
            "namespace" -> kind = Kind.NAMESPACE
            "fun", "def", "function", "func", "method" -> {
                kind = Kind.FUNCTION
                // 泛型 `fun <T> foo(...)`：跳过成对尖括号。
                if (j < toks.size && toks[j].text == "<") j = skipBalanced(toks, j, "<", ">")
                // Go 方法 `func (s *S) Foo(...)`：跳过接收者括号，记为方法。
                if (j < toks.size && toks[j].text == "(") {
                    j = skipBalanced(toks, j, "(", ")")
                    return symbolFrom(toks, j, Kind.METHOD, line, lineStart)
                }
            }
            "const", "let", "var" -> {
                // 仅把箭头函数 `const foo = ... => ...` 记为符号，普通变量跳过，避免大纲噪声。
                if (toks.none { it.text == "=" } || toks.none { it.text == ">" }) return null
                kind = Kind.VARIABLE
            }
            else -> return null
        }
        return symbolFrom(toks, j, kind, line, lineStart)
    }

    /** 从 [start] 起跳过标点取第一个标识符作为符号名，统计缩进，构造 [Symbol]。 */
    private fun symbolFrom(
        toks: List<Tok>, start: Int, kind: Kind, line: Int, lineStart: Int
    ): Symbol? {
        var j = start
        while (j < toks.size && !isIdentifier(toks[j].text)) j++
        if (j >= toks.size) return null
        val name = toks[j].text
        if (name in RESERVED) return null
        val indent = indentOf(toks)
        return Symbol(name, kind, line, lineStart + toks[j].start, indent)
    }

    /** 关键字之前只允许修饰词与 `@注解`，否则视为普通表达式误报。 */
    private fun prefixOk(toks: List<Tok>, k: Int): Boolean {
        var i = 0
        while (i < k) {
            val t = toks[i].text
            if (t == "@") {
                i++ // @
                if (i < k) i++ // 注解名
                continue
            }
            if (t in MODIFIERS) {
                i++
                continue
            }
            return false
        }
        return true
    }

    /** 跳过从 [start] 开始的成对分隔符，返回其后第一个 token 的下标。 */
    private fun skipBalanced(toks: List<Tok>, start: Int, open: String, close: String): Int {
        var depth = 0
        var j = start
        while (j < toks.size) {
            when (toks[j].text) {
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return j + 1
                }
            }
            j++
        }
        return toks.size
    }

    /** 前导空白字符数，等于首个 token 在原行内的起始下标；夹紧避免异常大缩进。 */
    private fun indentOf(toks: List<Tok>): Int =
        if (toks.isEmpty()) 0 else toks[0].start.coerceIn(0, 64)

    private fun isIdentifier(s: String): Boolean =
        s.isNotEmpty() && isIdentStart(s[0]) && s.all { isIdentPart(it) }

    private fun isIdentStart(c: Char): Boolean = c.isLetter() || c == '_' || c == '$'

    private fun isIdentPart(c: Char): Boolean = c.isLetterOrDigit() || c == '_' || c == '$'

    /** 与 splitLines 一致地计算每行在原文本中的起始偏移，兼容 CRLF / LF / CR。 */
    private fun lineStartOffsets(content: String): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        var i = 0
        while (i < content.length) {
            when {
                content[i] == '\r' && i + 1 < content.length && content[i + 1] == '\n' -> {
                    i += 2
                    starts.add(i)
                }
                content[i] == '\n' || content[i] == '\r' -> {
                    i += 1
                    starts.add(i)
                }
                else -> i++
            }
        }
        return starts.toIntArray()
    }

    private fun splitLines(content: String): List<String> =
        content.replace("\r\n", "\n").replace('\r', '\n').split('\n')

    companion object {
        const val DEFAULT_MAX_SYMBOLS: Int = 512
        private const val NO_QUOTE: Char = '\u0000'

        /** 声明关键字。 */
        private val DECL_KEYWORDS = setOf(
            "class", "interface", "object", "enum", "struct", "record",
            "fun", "def", "function", "func", "method", "namespace",
            "const", "let", "var"
        )

        /** 允许出现在声明关键字之前的修饰词。 */
        private val MODIFIERS = setOf(
            "public", "private", "protected", "internal", "open", "data", "sealed",
            "abstract", "inner", "final", "static", "override", "suspend", "inline",
            "tailrec", "operator", "infix", "external", "annotation", "lateinit",
            "const", "async", "export", "default", "virtual", "mutable", "readonly",
            "weak", "lazy", "companion", "ref", "val", "var", "let", "type"
        )

        /** 不得作为符号名的保留字（关键字错位的兜底）。 */
        private val RESERVED = setOf(
            "class", "interface", "object", "enum", "struct", "record", "fun",
            "def", "function", "func", "method", "namespace", "return", "if",
            "else", "when", "for", "while", "new", "override", "private", "public",
            "protected", "internal", "open", "data", "sealed", "abstract", "companion",
            "final", "static", "inline", "suspend", "async", "await", "type",
            "import", "package", "void", "int", "long", "string", "boolean",
            "this", "super", "class", "constructor", "init"
        )
    }
}
