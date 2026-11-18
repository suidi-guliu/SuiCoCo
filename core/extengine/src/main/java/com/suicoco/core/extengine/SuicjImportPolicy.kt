package com.suicoco.core.extengine

/**
 * .suicj 第三方导入安全策略（纯标准库实现，可独立编译/单测，不依赖 Android 或 zip4j）。
 *
 * 设计目标：第三方来源的 .suicj 属于不可信输入，必须在**解压落盘之前**完成静态审查，
 * 阻断 zip-slip（目录穿越）、解压炸弹、异常条目名与入口伪装。此对象只做「判定」，
 * 不触碰文件系统，便于在 JVM 单测中覆盖攻击样本。
 *
 * 对应 docs/PLUGIN_SPEC.md §7/§9 与 18:00 巡检日报 P2「第三方 .suicj 导入沙箱校验」。
 */
object SuicjImportPolicy {

    /** 单个 .suicj 允许的最大条目数（含目录条目）。 */
    const val MAX_ENTRIES = 512

    /** 单包解压后所有条目的未压缩字节总和上限（32 MiB）。 */
    const val MAX_TOTAL_UNCOMPRESSED = 32L * 1024 * 1024

    /** 单条目未压缩字节上限（16 MiB）。 */
    const val MAX_SINGLE_UNCOMPRESSED = 16L * 1024 * 1024

    /** 允许的入口扩展名。 */
    private val MAIN_EXT = ".js"

    /** 当前宿主引擎版本（随 RELEASE.md 递增；用于 engines.suicoco 兼容性判断）。 */
    const val HOST_VERSION = "0.2.8"

    /** ZIP 条目元信息（由调用方从 zip4j 的 FileHeader 填入，避免本类依赖 zip4j）。 */
    data class EntryInfo(
        val name: String,
        val uncompressedSize: Long,
        val compressedSize: Long,
        val isDirectory: Boolean
    )

    /** 审查结果：Pass 或含具体原因的拒绝。 */
    sealed class Verdict {
        object Pass : Verdict()
        data class Reject(val reason: String) : Verdict()
    }

    /**
     * 对 ZIP 条目清单做解压前审查。任一不安全即拒绝，调用方应据此中止解压。
     */
    fun review(entries: List<EntryInfo>): Verdict {
        if (entries.isEmpty()) return Verdict.Reject("插件包为空：不含任何条目")
        if (entries.size > MAX_ENTRIES) {
            return Verdict.Reject("条目数超限：${entries.size} > $MAX_ENTRIES")
        }

        var totalUncompressed = 0L
        for (e in entries) {
            val badName = unsafeNameReason(e.name)
            if (badName != null) return Verdict.Reject("非法条目名「${e.name}」：$badName")

            if (e.uncompressedSize < 0) {
                return Verdict.Reject("条目「${e.name}」大小非法：${e.uncompressedSize}")
            }
            if (!e.isDirectory && e.uncompressedSize > MAX_SINGLE_UNCOMPRESSED) {
                return Verdict.Reject(
                    "单文件过大：${e.name}（${e.uncompressedSize} 字节 > $MAX_SINGLE_UNCOMPRESSED）"
                )
            }
            // 高压缩比（解压炸弹）：仅对足够大的条目判定，避免小文件噪声误报
            if (!e.isDirectory && e.uncompressedSize >= 1024 && e.compressedSize > 0 &&
                e.uncompressedSize / e.compressedSize > MAX_RATIO
            ) {
                return Verdict.Reject(
                    "压缩比异常（疑似解压炸弹）：${e.name}（${e.uncompressedSize / e.compressedSize}:1）"
                )
            }
            totalUncompressed += e.uncompressedSize
            if (totalUncompressed > MAX_TOTAL_UNCOMPRESSED) {
                return Verdict.Reject("解压后总大小超限：$totalUncompressed > $MAX_TOTAL_UNCOMPRESSED")
            }
        }
        return Verdict.Pass
    }

    private const val MAX_RATIO = 200L

    /**
     * 校验单条目名是否安全。返回 null 表示安全，否则返回拒绝原因。
     * 拒绝：空名、绝对路径、盘符、反斜杠（Windows 分隔）、"/"、".." 段、NUL/控制字符。
     */
    fun unsafeNameReason(name: String): String? {
        if (name.isEmpty()) return "空条目名"
        if (name.contains('\u0000')) return "包含 NUL 字符"
        if (name.any { it.code < 0x20 }) return "包含控制字符"
        if (name.startsWith("/") || name.startsWith("\\")) return "绝对路径"
        if (name.length >= 2 && name[1] == ':') return "含 Windows 盘符"
        if (name.contains('\\')) return "含反斜杠（目录穿越风险）"
        // 目录条目惯例以单个 '/' 结尾，去掉后再逐段检查；仍拒绝中间空段（双斜杠）
        val normalized = if (name.endsWith("/")) name.dropLast(1) else name
        if (normalized.isEmpty()) return "非法条目名"
        val segments = normalized.split('/')
        for (seg in segments) {
            when (seg) {
                ".." -> return "含 '..' 父目录段（目录穿越）"
                "." -> return "含 '.' 段"
                "" -> return "含空路径段（如双斜杠）"
            }
        }
        return null
    }

    /** manifest.main 入口路径必须安全且以 .js 结尾。 */
    fun isSafeMain(main: String): Boolean {
        if (main.isBlank()) return false
        if (unsafeNameReason(main) != null) return false
        return main.endsWith(MAIN_EXT, ignoreCase = true)
    }

    /**
     * 语义化版本三元组比较。返回负数/0/正数分别表示 a<b / a==b / a>b。
     * 忽略 pre-release/build 后缀，仅比较 major.minor.patch 数字段。
     */
    fun compareVersion(a: String, b: String): Int {
        val va = parseTriple(a) ?: return Int.MIN_VALUE
        val vb = parseTriple(b) ?: return Int.MAX_VALUE
        return when {
            va.first != vb.first -> va.first.compareTo(vb.first)
            va.second != vb.second -> va.second.compareTo(vb.second)
            else -> va.third.compareTo(vb.third)
        }
    }

    private fun parseTriple(v: String): Triple<Int, Int, Int>? {
        val core = v.trim().substringBefore('-').substringBefore('+')
        val parts = core.split('.')
        if (parts.size < 3) return null
        val nums = parts.take(3).map { it.toIntOrNull() ?: return null }
        return Triple(nums[0], nums[1], nums[2])
    }

    /**
     * 判断宿主版本 host 是否满足 engines.suicoco 声明的范围。
     * 支持：'*'、精确 'x.y.z'、'^x.y.z'（同 major）、'>=x.y.z'。
     * 无法解析的范围一律拒绝（fail-closed）。
     */
    fun isEngineCompatible(range: String, host: String = HOST_VERSION): Boolean {
        val r = range.trim()
        if (r.isEmpty()) return false
        if (r == "*") return true
        if (parseTriple(host) == null) return false
        return when {
            r.startsWith(">=") -> {
                val base = r.removePrefix(">=").trim()
                parseTriple(base)?.let { compareVersion(host, base) >= 0 } ?: false
            }
            r.startsWith("^") -> {
                val base = r.removePrefix("^").trim()
                val h = parseTriple(host) ?: return false
                val t = parseTriple(base) ?: return false
                h.first == t.first && compareVersion(host, base) >= 0
            }
            else -> parseTriple(r)?.let { compareVersion(host, r) == 0 } ?: false
        }
    }
}
