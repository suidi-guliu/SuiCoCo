package com.suicoco.feature.settings

import com.suicoco.core.config.PluginRepositoryConfig

/**
 * 官方插件仓库地址配置控制器（纯逻辑，与 Compose / Android 解耦，便于本地编译单测）。
 *
 * 对应 06:00 巡检日报 P0「阶段6 收尾——官方插件仓库地址配置项」：设置页接收用户输入，
 * 复用 core:config 的 [PluginRepositoryConfig] 做 fail-closed 校验，返回可展示的判定结果。
 * 本类不持久化、不发起网络，仅做输入 → 判定的纯映射，便于在 JVM 单测覆盖。
 */
object RepositoryConfigController {

    /** 单条校验的可展示结果。 */
    sealed class FieldResult {
        data class Ok(val normalizedUrl: String, val catalogUrl: String) : FieldResult()
        data class Error(val reason: String) : FieldResult()
    }

    /** 初始输入（默认官方仓库地址）。 */
    fun defaultUrl(): String = PluginRepositoryConfig.DEFAULT_REPOSITORY_URL

    /**
     * 校验输入并返回展示结果。合法时同时给出规范化地址与清单 URL，非法时给出原因。
     */
    fun validate(input: String): FieldResult = when (val v = PluginRepositoryConfig.validate(input)) {
        is PluginRepositoryConfig.Verdict.Valid -> {
            val catalog = PluginRepositoryConfig.catalogUrl(v.normalizedUrl)
            if (catalog == null) {
                FieldResult.Error("无法构造插件清单地址，请检查仓库地址")
            } else {
                FieldResult.Ok(v.normalizedUrl, catalog)
            }
        }
        is PluginRepositoryConfig.Verdict.Invalid -> FieldResult.Error(v.reason)
    }
}
