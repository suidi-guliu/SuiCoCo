package com.suicoco.core.config

import java.security.MessageDigest

/**
 * 官方插件仓库清单（release/suicj-catalog.json）安全解析内核。
 *
 * 纯 Kotlin/Java 标准库实现，不依赖 Android / Compose / 网络 / JSON 三方库，
 * 可独立 kotlinc 编译并单测。对应 18:00 巡检日报 P0「阶段6 后续功能」——
 * 把 v0.3.0 的「输入框实时校验」升级为「清单可解析、可下载、可校验」的真实仓库配置基础。
 *
 * 设计原则（fail-closed）：清单是用户可配置仓库下载的可执行插件索引，属高价值攻击面。
 * 任何不合法、超限、畸形输入一律返回 [ParseResult.Failure]，绝不抛出、绝不返回部分结果；
 * 下载地址构造一律复用 [PluginRepositoryConfig] 的白名单判定，避免绕过域名校验。
 */
object PluginCatalog {

    /** 清单文本最大字节数（防超大清单拖垮解析）。 */
    const val MAX_CATALOG_BYTES = 512 * 1024
    /** 清单条目数上限。 */
    const val MAX_ENTRIES = 128
    /** 单条目文件名最大长度。 */
    const val MAX_FILE_NAME = 128
    /** 当前支持的清单结构版本。 */
    const val SUPPORTED_VERSION = 1
    /** 允许的 requiresEnv 白名单（与 PLUGIN_SPEC §3 一致）。 */
    private val ALLOWED_ENV = setOf("cpp", "python", "java")

    /** 清单条目：一条官方插件的可下载描述。 */
    data class Entry(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val file: String,
        val sha256: String,
        val engine: String,
        val requiresEnv: String?
    )

    /** 解析结果：成功（结构版本 + 条目）或失败（具体原因）。 */
    sealed class ParseResult {
        data class Success(val version: Int, val entries: List<Entry>) : ParseResult()
        data class Failure(val reason: String) : ParseResult()
    }

    /**
     * 解析并安全校验清单文本。任一异常/不合法即 [ParseResult.Failure]，不抛异常。
     */
    fun parse(json: String): ParseResult {
      return try {
        val bytes = json.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty()) return ParseResult.Failure("清单为空")
        if (bytes.size > MAX_CATALOG_BYTES) return ParseResult.Failure("清单过大（>${MAX_CATALOG_BYTES} 字节）")

        val root = Json.parse(json) as? Map<*, *>
            ?: return ParseResult.Failure("清单根节点不是对象")

        val version = (root["version"] as? Number)?.toInt()
            ?: return ParseResult.Failure("清单缺少整型 version")
        if (version != SUPPORTED_VERSION) return ParseResult.Failure("不支持的清单版本：$version")

        val rawEntries = root["plugins"] as? List<*>
            ?: return ParseResult.Failure("清单缺少 plugins 数组")
        if (rawEntries.size > MAX_ENTRIES) return ParseResult.Failure("清单条目数超限（>${MAX_ENTRIES}）")

        val seenIds = HashSet<String>()
        val seenFiles = HashSet<String>()
        val entries = ArrayList<Entry>(rawEntries.size)
        for (i in rawEntries.indices) {
            val raw = rawEntries[i] as? Map<*, *>
                ?: return ParseResult.Failure("plugins[$i] 不是对象")
            val id = str(raw, "id") ?: return ParseResult.Failure("plugins[$i] 缺少字符串 id")
            if (!validId(id)) return ParseResult.Failure("plugins[$i] id 非法：$id")
            if (!seenIds.add(id)) return ParseResult.Failure("plugins[$i] id 重复：$id")

            val name = str(raw, "name") ?: return ParseResult.Failure("plugins[$i] 缺少字符串 name")
            if (name.isEmpty()) return ParseResult.Failure("plugins[$i] name 为空")

            val ver = str(raw, "version") ?: return ParseResult.Failure("plugins[$i] 缺少字符串 version")
            if (!validVersion(ver)) return ParseResult.Failure("plugins[$i] version 非法：$ver")

            val desc = str(raw, "description") ?: ""

            val file = str(raw, "file") ?: return ParseResult.Failure("plugins[$i] 缺少字符串 file")
            if (!validFileName(file)) return ParseResult.Failure("plugins[$i] file 非法：$file")
            if (!seenFiles.add(file)) return ParseResult.Failure("plugins[$i] file 重复：$file")

            val sha = str(raw, "sha256") ?: return ParseResult.Failure("plugins[$i] 缺少字符串 sha256")
            if (!isHex64(sha)) return ParseResult.Failure("plugins[$i] sha256 非法（须 64 位十六进制）：$sha")

            val engine = str(raw, "engine") ?: return ParseResult.Failure("plugins[$i] 缺少字符串 engine")
            if (!validEngineRange(engine)) return ParseResult.Failure("plugins[$i] engine 非法：$engine")

            val env = raw["requiresEnv"]
            val envStr = when {
                env == null || env == "null" -> null
                env is String -> env
                else -> return ParseResult.Failure("plugins[$i] requiresEnv 类型非法")
            }
            if (envStr != null && envStr !in ALLOWED_ENV) {
                return ParseResult.Failure("plugins[$i] requiresEnv 不在白名单：$envStr")
            }

            entries.add(Entry(id, name, ver, desc, file, sha.lowercase(), engine, envStr))
        }
        ParseResult.Success(version, entries)
      } catch (e: Exception) {
        ParseResult.Failure("清单解析异常：${e.message ?: e::class.simpleName}")
      }
    }

    /**
     * 由仓库根地址 + 条目文件名构造官方插件下载地址。
     * 仅当仓库地址通过 [PluginRepositoryConfig] 白名单校验且文件名安全时返回，否则 null（fail-closed）。
     */
    fun downloadUrl(repositoryUrl: String, file: String): String? {
        if (!validFileName(file)) return null
        val v = PluginRepositoryConfig.validate(repositoryUrl) as? PluginRepositoryConfig.Verdict.Valid
            ?: return null
        return "${v.normalizedUrl}/${PluginRepositoryConfig.RELEASES_PATH}/$file"
    }

    /** 清单文件 URL（复用内核目录常量，供 UI/下载层直接调用）。 */
    fun catalogUrl(repositoryUrl: String): String? = PluginRepositoryConfig.catalogUrl(repositoryUrl)

    /** 计算字节数组的 SHA-256 十六进制串（小写），用于下载后校验。 */
    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    /** 下载内容 SHA-256 是否与清单声明一致（常量时间比较，避免时序侧信道）。 */
    fun verifyChecksum(bytes: ByteArray, expectedSha256: String): Boolean {
        if (!isHex64(expectedSha256)) return false
        val actual = sha256Hex(bytes)
        var diff = actual.length xor expectedSha256.length
        for (i in actual.indices) {
            diff = diff or (actual[i].code xor expectedSha256.lowercase()[i].code)
        }
        return diff == 0
    }

    /** 语义化版本兼容判定：支持 `*`、`x.y.z`、`^x.y.z`、`>=x.y.z`；无法解析一律 false。 */
    fun isEngineCompatible(engine: String, hostVersion: String): Boolean {
        if (engine == "*") return true
        val host = parseVersion(hostVersion) ?: return false
        return when {
            engine.startsWith(">=") -> {
                val v = parseVersion(engine.removePrefix(">=").trim()) ?: return false
                compare(host, v) >= 0
            }
            engine.startsWith("^") -> {
                val v = parseVersion(engine.removePrefix("^").trim()) ?: return false
                host.first == v.first && compare(host, v) >= 0
            }
            else -> parseVersion(engine)?.let { compare(host, it) == 0 } ?: false
        }
    }

    // ---- 内部校验工具 ----

    private val HEX = "0123456789abcdef".toCharArray()

    private fun str(map: Map<*, *>, key: String): String? = map[key] as? String

    private fun validId(id: String): Boolean =
        id.length in 3..128 && id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.[A-Za-z0-9._-]+"))

    private fun validVersion(v: String): Boolean = parseVersion(v) != null

    private fun validFileName(name: String): Boolean {
        if (name.isEmpty() || name.length > MAX_FILE_NAME) return false
        if (!name.endsWith(".suicj", ignoreCase = true)) return false
        // 拒绝路径分隔、相对跳转、反斜杠、控制字符、冒号与空白开头（防 zip-slip / 伪装）
        if (name.contains('/') || name.contains('\\') || name == ".." || name.contains("../")) return false
        if (name.any { it.isISOControl() || it == ':' }) return false
        return name.first().isLetterOrDigit()
    }

    private fun isHex64(s: String): Boolean =
        s.length == 64 && s.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

    private fun validEngineRange(engine: String): Boolean {
        if (engine.isEmpty() || engine.length > 32) return false
        if (engine == "*") return true
        if (engine.startsWith(">=") || engine.startsWith("^")) {
            return parseVersion(engine.substring(2).trim()) != null
        }
        return parseVersion(engine) != null
    }

    private fun parseVersion(v: String): Triple<Int, Int, Int>? {
        val parts = v.split('.')
        if (parts.size != 3) return null
        val nums = parts.map { it.toIntOrNull() ?: return null }
        if (nums.any { it < 0 }) return null
        return Triple(nums[0], nums[1], nums[2])
    }

    private fun compare(a: Triple<Int, Int, Int>, b: Triple<Int, Int, Int>): Int {
        if (a.first != b.first) return a.first.compareTo(b.first)
        if (a.second != b.second) return a.second.compareTo(b.second)
        return a.third.compareTo(b.third)
    }

    /** 极简 JSON 解析器（递归下降，标准库实现，足够解析清单结构）。 */
    private object Json {
        fun parse(text: String): Any? {
            val p = Parser(text)
            val value = p.parseValue()
            p.skipWs()
            if (!p.eof()) throw IllegalArgumentException("JSON 尾部存在多余内容")
            return value
        }

        private class Parser(private val s: String) {
            private var i = 0
            fun eof() = i >= s.length
            fun skipWs() {
                while (i < s.length && s[i].isWhitespace()) i++
            }

            fun parseValue(): Any? {
                skipWs()
                if (eof()) throw IllegalArgumentException("JSON 意外结束")
                return when (s[i]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't' -> { expect("true"); true }
                    'f' -> { expect("false"); false }
                    'n' -> { expect("null"); null }
                    else -> parseNumber()
                }
            }

            private fun expect(token: String) {
                if (!s.startsWith(token, i)) throw IllegalArgumentException("期望 $token")
                i += token.length
            }

            private fun parseObject(): Map<String, Any?> {
                i++ // {
                val map = LinkedHashMap<String, Any?>()
                skipWs()
                if (!eof() && s[i] == '}') { i++; return map }
                while (true) {
                    skipWs()
                    val key = parseString()
                    skipWs()
                    if (eof() || s[i] != ':') throw IllegalArgumentException("JSON 对象缺少冒号")
                    i++
                    map[key] = parseValue()
                    skipWs()
                    if (eof()) throw IllegalArgumentException("JSON 对象未闭合")
                    when (s[i]) {
                        ',' -> i++
                        '}' -> { i++; return map }
                        else -> throw IllegalArgumentException("JSON 对象非法分隔符")
                    }
                }
            }

            private fun parseArray(): List<Any?> {
                i++ // [
                val list = ArrayList<Any?>()
                skipWs()
                if (!eof() && s[i] == ']') { i++; return list }
                while (true) {
                    list.add(parseValue())
                    skipWs()
                    if (eof()) throw IllegalArgumentException("JSON 数组未闭合")
                    when (s[i]) {
                        ',' -> i++
                        ']' -> { i++; return list }
                        else -> throw IllegalArgumentException("JSON 数组非法分隔符")
                    }
                }
            }

            private fun parseString(): String {
                if (eof() || s[i] != '"') throw IllegalArgumentException("期望 JSON 字符串")
                i++
                val sb = StringBuilder()
                while (i < s.length) {
                    val c = s[i++]
                    if (c == '"') return sb.toString()
                    if (c == '\\') {
                        if (i >= s.length) throw IllegalArgumentException("JSON 转义未结束")
                        when (val e = s[i++]) {
                            '"', '\\', '/' -> sb.append(e)
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (i + 4 > s.length) throw IllegalArgumentException("JSON unicode 转义不完整")
                                val hex = s.substring(i, i + 4)
                                val cp = hex.toIntOrNull(16) ?: throw IllegalArgumentException("非法 unicode 转义：$hex")
                                sb.append(cp.toChar())
                                i += 4
                            }
                            else -> throw IllegalArgumentException("非法 JSON 转义：$e")
                        }
                    } else {
                        if (c.isISOControl()) throw IllegalArgumentException("JSON 字符串含控制字符")
                        sb.append(c)
                    }
                }
                throw IllegalArgumentException("JSON 字符串未闭合")
            }

            private fun parseNumber(): Long {
                val start = i
                if (!eof() && s[i] == '-') i++
                while (!eof() && s[i].isDigit()) i++
                if (start == i || (s[start] == '-' && i == start + 1)) {
                    throw IllegalArgumentException("非法 JSON 数字")
                }
                return s.substring(start, i).toLongOrNull()
                    ?: throw IllegalArgumentException("JSON 数字溢出")
            }
        }
    }
}
