package com.suicoco.core.config

import java.net.URI

/**
 * 官方插件仓库地址配置（纯标准库实现，可独立编译/单测，不依赖 Android）。
 *
 * 对应 docs/PLUGIN_SPEC.md §8「官方插件从 GitHub 仓库 release/ 下载，设置中可配置仓库地址」，
 * 以及历轮巡检日报 P2「阶段6 前置——官方插件仓库地址配置」。
 *
 * 设计目标：插件仓库地址属于用户可配置输入，但插件包会被下载并执行 JS，属于高价值攻击面。
 * 本对象只做「地址的静态判定与规范化」——不发起网络、不触碰文件，便于 JVM 单测覆盖 SSRF /
 * 域名欺骗 / 凭证注入等样本。UI 层（feature:settings / feature:marketplace）据此 fail-closed。
 */
object PluginRepositoryConfig {

    /** 默认官方仓库根地址（项目权威仓库）。 */
    const val DEFAULT_REPOSITORY_URL = "https://github.com/suidi-guliu/SuiCoCo"

    /** 官方 release 产物所在子路径（相对仓库根，拼接后指向 release/ 清单）。 */
    const val RELEASES_PATH = "release"

    /**
     * 允许的仓库域名白名单（大小写不敏感）。
     * 仅信任项目权威仓库所在域名，避免把用户引导到仿冒/恶意源下载可执行插件。
     */
    private val ALLOWED_HOSTS = setOf(
        "github.com",
        "raw.githubusercontent.com",
        "objects.githubusercontent.com"
    )

    /** 判定结果：Valid（含规范化地址）或 Invalid（含具体原因）。 */
    sealed class Verdict {
        data class Valid(val normalizedUrl: String, val host: String) : Verdict()
        data class Invalid(val reason: String) : Verdict()
    }

    /**
     * 校验并规范化用户配置的仓库根地址。任一不安全即拒绝（fail-closed）。
     * 规则：
     *  - 非空、可解析为 URI；
     *  - 协议必须为 https（禁 http/ftp/file 等）；
     *  - 不得包含用户信息（user:pass@，凭证注入/混淆）；
     *  - host 必须命中白名单；
     *  - 拒绝 IP 字面量（IPv4/IPv6）与回环/私网，防 SSRF 与域名欺骗；
     *  - 端口只能缺省或 443；
     *  - fragment 不允许；path 段不得含 '..' 越界。
     */
    fun validate(raw: String): Verdict {
        val value = raw.trim()
        if (value.isEmpty()) return Verdict.Invalid("仓库地址为空")
        val uri = try {
            URI.create(value)
        } catch (e: IllegalArgumentException) {
            return Verdict.Invalid("仓库地址无法解析：${e.message ?: "非法 URI"}")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "https") return Verdict.Invalid("仅允许 https 协议，当前为：${scheme ?: "无"}")

        if (uri.userInfo != null) return Verdict.Invalid("仓库地址不得包含用户信息（user:pass@）")

        val host = uri.host?.lowercase()
            ?: return Verdict.Invalid("仓库地址缺少 host")
        if (isIpLiteral(host)) return Verdict.Invalid("不允许使用 IP 字面量作为仓库地址：$host")
        if (host !in ALLOWED_HOSTS) return Verdict.Invalid("仓库域名不在白名单内：$host")

        if (uri.port != -1 && uri.port != 443) {
            return Verdict.Invalid("仓库地址端口非法（仅允许缺省或 443）：${uri.port}")
        }
        if (uri.fragment != null) return Verdict.Invalid("仓库地址不得包含 fragment")

        val path = uri.path ?: ""
        if (path.split('/').any { it == ".." }) {
            return Verdict.Invalid("仓库地址路径含 '..' 越界段")
        }

        // 规范化：去掉尾部斜杠，保留 scheme://host/path
        val normalized = buildString {
            append("https://").append(host)
            if (path.isNotEmpty()) append(path.trimEnd('/'))
        }
        return Verdict.Valid(normalized, host)
    }

    /** 是否为可用的官方仓库地址（校验的布尔封装，便于 UI/配置层 fail-closed 调用）。 */
    fun isValid(raw: String): Boolean = validate(raw) is Verdict.Valid

    /**
     * 由仓库根地址构造官方插件清单 URL（release/suicj-catalog.json）。
     * 仅在地址合法时返回，非法返回 null（调用方应拒绝并提示）。
     */
    fun catalogUrl(repositoryUrl: String): String? {
        val v = validate(repositoryUrl) as? Verdict.Valid ?: return null
        return "${v.normalizedUrl}/$RELEASES_PATH/suicj-catalog.json"
    }

    /** IPv4 / IPv6 字面量判定（含方括号 IPv6）。 */
    private fun isIpLiteral(host: String): Boolean {
        val h = host.removePrefix("[").removeSuffix("]")
        if (h.contains(':')) return true // IPv6 字面量
        val parts = h.split('.')
        if (parts.size != 4) return false
        return parts.all { seg ->
            seg.isNotEmpty() && seg.length <= 3 && seg.all { it.isDigit() } &&
                seg.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
