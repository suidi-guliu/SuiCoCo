package com.suicoco.feature.settings

import android.content.Context

/**
 * 官方插件仓库地址的跨会话持久化（feature:settings 层）。
 *
 * 对应 18:00 巡检日报 P0「阶段6 后续功能——仓库地址跨会话持久化」。
 * 采用 Android 平台自带 SharedPreferences，无需新增三方依赖（CI 安全）。
 * 仅用 Android framework API；所有地址判定一律委托纯逻辑 [RepositoryConfigController]，
 * 本类不自行解析/信任任何字符串，保证 fail-closed 不被绕过。
 */
class RepositoryConfigStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 读取已保存地址；无论存储值是否被篡改，都经白名单净化后才返回。 */
    fun load(): String = RepositoryConfigController.sanitizeLoaded(prefs.getString(KEY_URL, null))

    /** 保存地址；非法输入一律拒绝写入并返回 false，合法则规范化后落盘。 */
    fun save(input: String): Boolean {
        val normalized = RepositoryConfigController.normalizeOrNull(input) ?: return false
        prefs.edit().putString(KEY_URL, normalized).apply()
        return true
    }

    private companion object {
        const val PREFS_NAME = "suicoco_settings"
        const val KEY_URL = "plugin_repository_url"
    }
}
