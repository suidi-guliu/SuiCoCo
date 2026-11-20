package com.suicoco.feature.marketplace

import com.suicoco.core.config.PluginCatalog
import com.suicoco.core.config.PluginRepositoryConfig
import java.net.HttpURLConnection
import java.net.URI

/**
 * 官方插件「一键下载 + 自动安装」编排内核（纯逻辑，可独立编译单测）。
 *
 * 对应 2026-11-20 06:03 巡检日报 P0「阶段6 后续余项：基于清单的官方插件一键下载 + 自动安装
 * UI 交互（内核与 downloadUrl() 已就绪，UI 未接）」。本对象把已就绪的 [PluginCatalog] 清单内核
 * 与 UI/网络层之间的「编排 + 安全判定」集中起来，不依赖 Android / Compose / 网络库：
 *  - 清单解析与引擎兼容过滤；
 *  - 把 [PluginCatalog.downloadUrl] 生成的 GitHub 网页 URL 安全转换为 raw 二进制直链
 *    （网页 URL 直接下载只会得到 HTML，必须转换）；
 *  - 下载后 SHA256 校验（复用内核常量时间比对）；
 *  - 网络重定向目标的白名单判定（防 SSRF / 域名欺骗）。
 *
 * 网络 I/O、文件写入与 Compose 状态由宿主 app 层注入，本类只做输入 → 判定的纯映射，
 * 保证 fail-closed 关键规则可在 JVM 上严格单测，CI 编译安全。
 */
object OfficialInstallController {

    /** 展示层用的一条官方插件信息（含引擎兼容标记）。 */
    data class CatalogItem(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val file: String,
        val sha256: String,
        val engine: String,
        val requiresEnv: String?,
        val compatible: Boolean
    )

    /** 清单加载结果。 */
    sealed class CatalogResult {
        data class Success(val version: Int, val items: List<CatalogItem>) : CatalogResult()
        data class Failure(val reason: String) : CatalogResult()
    }

    /** 下载内容校验结果。 */
    sealed class VerifyResult {
        data class Ok(val item: CatalogItem) : VerifyResult()
        data class Fail(val reason: String) : VerifyResult()
    }

    /** 单次 HTTP 取数的抽象结果：由 app 层用 HttpURLConnection 产生，内核据此做判定。 */
    sealed class FetchStep {
        data class Ok(val bytes: ByteArray) : FetchStep()
        data class Redirect(val location: String) : FetchStep()
        data class Fail(val reason: String) : FetchStep()
    }

    /** 带重定向的最终下载结果。 */
    sealed class DownloadResult {
        data class Ok(val bytes: ByteArray) : DownloadResult()
        data class Fail(val reason: String) : DownloadResult()
    }

    /**
     * 受控重定向下载编排：每一步跟随前都用 [isAllowedDownloadTarget] 校验目标，
     * 任何指向非白名单域名的 302 一律拒绝，杜绝「官方源重定向到内网/仿冒站」的 SSRF。
     * [fetch] 由 app 层注入（内联网络 I/O），本函数只做状态机判定，可离线严格单测。
     */
    fun fetchWithGuardedRedirects(
        startUrl: String,
        fetch: (String) -> FetchStep,
        maxRedirects: Int = 5
    ): DownloadResult {
        if (!isAllowedDownloadTarget(startUrl)) {
            return DownloadResult.Fail("下载地址不在白名单：$startUrl")
        }
        var current = startUrl
        for (hop in 0..maxRedirects) {
            val step = try {
                fetch(current)
            } catch (e: Exception) {
                return DownloadResult.Fail("下载异常：${e.message ?: e::class.simpleName}")
            }
            when (step) {
                is FetchStep.Ok -> return DownloadResult.Ok(step.bytes)
                is FetchStep.Fail -> return DownloadResult.Fail(step.reason)
                is FetchStep.Redirect -> {
                    if (hop == maxRedirects) return DownloadResult.Fail("重定向次数超限")
                    if (!isAllowedDownloadTarget(step.location)) {
                        return DownloadResult.Fail("拒绝跟随重定向到非白名单地址：${step.location}")
                    }
                    current = step.location
                }
            }
        }
        return DownloadResult.Fail("下载失败")
    }

    /**
     * 解析清单文本并按宿主版本标注引擎兼容性。
     * 任何畸形 / 超限 / 非法清单都 fail-closed 为 [CatalogResult.Failure]。
     */
    fun loadCatalog(catalogJson: String, hostVersion: String): CatalogResult {
        if (hostVersion.isBlank()) return CatalogResult.Failure("宿主版本为空")
        return when (val parsed = PluginCatalog.parse(catalogJson)) {
            is PluginCatalog.ParseResult.Failure -> CatalogResult.Failure(parsed.reason)
            is PluginCatalog.ParseResult.Success -> CatalogResult.Success(
                version = parsed.version,
                items = parsed.entries.map { e ->
                    CatalogItem(
                        id = e.id,
                        name = e.name,
                        version = e.version,
                        description = e.description,
                        file = e.file,
                        sha256 = e.sha256,
                        engine = e.engine,
                        requiresEnv = e.requiresEnv,
                        compatible = PluginCatalog.isEngineCompatible(e.engine, hostVersion)
                    )
                }
            )
        }
    }

    /**
     * 由仓库根地址 + 清单条目文件名，构造可真正下载到二进制的 raw 直链。
     *
     * [PluginCatalog.downloadUrl] 先做文件名安全校验与仓库地址白名单校验（fail-closed），
     * 但它返回的是 github.com 网页 URL（GET 只会得到 HTML）。本方法在其校验通过的基础上，
     * 仅对 github.com 的 `<owner>/<repo>/release/<file>` 结构做等价的 raw.githubusercontent.com
     * 直链转换；其它合法形态（已是 raw / release asset）原样返回。转换后再次白名单校验。
     * 任一环节失败返回 null（调用方应拒绝下载）。
     */
    fun resolveDownloadUrl(repositoryUrl: String, file: String): String? {
        val webUrl = PluginCatalog.downloadUrl(repositoryUrl, file) ?: return null
        val uri = try {
            URI.create(webUrl)
        } catch (e: IllegalArgumentException) {
            return null
        }
        val scheme = uri.scheme?.lowercase()
        val host = uri.host?.lowercase() ?: return null
        if (scheme != "https") return null
        return when (host) {
            "github.com" -> {
                // 期望路径：/owner/repo/release/file.suicj
                val seg = uri.path?.trim('/')?.split('/') ?: return null
                if (seg.size != 4 || seg[2] != PluginRepositoryConfig.RELEASES_PATH) return null
                val owner = seg[0]
                val repo = seg[1]
                if (owner.isEmpty() || repo.isEmpty()) return null
                // 关键：保留 release/ 段（seg[2]），否则 raw 直链指向仓库根、返回 404。
                val raw = "https://raw.githubusercontent.com/$owner/$repo/main/${seg[2]}/${seg[3]}"
                if (isAllowedDownloadTarget(raw)) raw else null
            }
            "raw.githubusercontent.com", "objects.githubusercontent.com" ->
                if (isAllowedDownloadTarget(webUrl)) webUrl else null
            else -> null
        }
    }

    /**
     * 清单文件本身的 raw 下载地址（下载 suicj-catalog.json 用）。
     * 与 [resolveDownloadUrl] 同构：先经内核 catalogUrl() 校验，再转 raw 直链。
     */
    fun resolveCatalogUrl(repositoryUrl: String): String? {
        val webUrl = PluginCatalog.catalogUrl(repositoryUrl) ?: return null
        val uri = try {
            URI.create(webUrl)
        } catch (e: IllegalArgumentException) {
            return null
        }
        if (uri.scheme?.lowercase() != "https") return null
        val host = uri.host?.lowercase() ?: return null
        return when (host) {
            "github.com" -> {
                val seg = uri.path?.trim('/')?.split('/') ?: return null
                if (seg.size != 4 || seg[2] != PluginRepositoryConfig.RELEASES_PATH) return null
                val raw = "https://raw.githubusercontent.com/${seg[0]}/${seg[1]}/main/${seg[2]}/${seg[3]}"
                if (isAllowedDownloadTarget(raw)) raw else null
            }
            "raw.githubusercontent.com", "objects.githubusercontent.com" ->
                if (isAllowedDownloadTarget(webUrl)) webUrl else null
            else -> null
        }
    }

    /**
     * 网络重定向目标是否允许跟随（防 SSRF）。规则与 [PluginRepositoryConfig] 一致：
     * https、host 命中白名单、无用户信息、端口缺省或 443、无 fragment、路径无 '..'。
     * 这样即使上游返回的 302 指向内网或仿冒域名，也一律拒绝跟随。
     */
    fun isAllowedDownloadTarget(url: String): Boolean {
        val v = try {
            PluginRepositoryConfig.validate(url)
        } catch (e: Exception) {
            return false
        }
        return v is PluginRepositoryConfig.Verdict.Valid
    }

    /**
     * 真实网络取数实现（标准库 HttpURLConnection），供宿主注入 [fetchWithGuardedRedirects]。
     * 关闭自动重定向，把 3xx 交给内核做白名单判定后再跟随，杜绝 SSRF。
     * 本方法含网络 I/O，单测不调用；纯判定逻辑已由 [fetchWithGuardedRedirects] 覆盖。
     */
    fun networkFetch(url: String): FetchStep {
        var conn: HttpURLConnection? = null
        return try {
            conn = URI.create(url).toURL().openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.requestMethod = "GET"
            when (val code = conn.responseCode) {
                HttpURLConnection.HTTP_OK -> FetchStep.Ok(conn.inputStream.use { it.readBytes() })
                in 300..399 -> {
                    val loc = conn.getHeaderField("Location")
                    if (loc.isNullOrBlank()) {
                        FetchStep.Fail("重定向响应缺少 Location")
                    } else {
                        val abs = try {
                            URI.create(url).resolve(loc).toString()
                        } catch (e: IllegalArgumentException) {
                            return FetchStep.Fail("非法重定向地址：$loc")
                        }
                        FetchStep.Redirect(abs)
                    }
                }
                else -> FetchStep.Fail("下载失败：HTTP $code")
            }
        } catch (e: Exception) {
            FetchStep.Fail("网络异常：${e.message ?: e::class.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    /**
     * 校验下载字节与清单声明的 SHA-256 是否一致（常量时间比对，复用内核实现）。
     * 这是「官方来源仍不可信」原则的落点：即便从官方 raw 下载，也必须先校验摘要再导入。
     */
    fun verifyDownloaded(bytes: ByteArray, item: CatalogItem): VerifyResult {
        if (bytes.isEmpty()) return VerifyResult.Fail("下载内容为空")
        if (!isAllowedDownloadTarget("https://raw.githubusercontent.com/x/y/main/${item.file}")) {
            return VerifyResult.Fail("插件文件名非法")
        }
        if (!PluginCatalog.verifyChecksum(bytes, item.sha256)) {
            return VerifyResult.Fail("SHA-256 校验失败：下载内容与官方清单不一致，已拒绝安装")
        }
        return VerifyResult.Ok(item)
    }
}
