package com.suicoco.feature.marketplace

import com.suicoco.core.extengine.SuicjException
import com.suicoco.core.extengine.SuicjPackage
import java.io.File

/**
 * 第三方 .suicj 导入控制器（纯逻辑，与 Compose / Android UI 解耦，便于本地编译单测）。
 *
 * 对应 docs/PLUGIN_SPEC.md §8「第三方插件：软件内『导入 .suicj』按钮选择本地文件，
 * 导入时执行 §7 全部校验，失败即拒绝并提示原因」，以及 06:00 巡检日报 P0「阶段6 收尾」。
 *
 * 实际解包与全部安全审查复用 core:extengine 的 [SuicjPackage]（内部经 SuicjImportPolicy
 * 做解压前 zip-slip / 解压炸弹 / 入口伪装 / 引擎不兼容拦截），本类不重复实现校验，
 * 只负责把结果映射为 UI 可展示的状态，并把所有异常 fail-closed 为拒绝原因。
 */
object ImportController {

    /** 导入成功后供 UI / app 层登记的插件摘要（避免 feature 依赖 app 的数据模型）。 */
    data class ImportedPluginInfo(
        val id: String,
        val name: String,
        val version: String,
        val description: String,
        val main: String
    )

    /** 导入结果：成功（含摘要）或拒绝（含具体原因，供 UI 展示）。 */
    sealed class ImportResult {
        data class Success(val plugin: ImportedPluginInfo) : ImportResult()
        data class Rejected(val reason: String) : ImportResult()
    }

    /**
     * 校验并导入一个本地 .suicj 文件。
     * 任何异常都不会抛出，而是一律映射为 [ImportResult.Rejected]，保证 UI 始终能展示原因。
     */
    fun import(source: File, workDir: File): ImportResult {
        if (!source.name.endsWith(".suicj", ignoreCase = true)) {
            return ImportResult.Rejected("不支持的格式：仅接受 .suicj（不兼容 .vsix/.jext 等其他格式）")
        }
        if (!source.exists() || source.length() == 0L) {
            return ImportResult.Rejected("插件文件不存在或为空")
        }
        return try {
            val unpacked = SuicjPackage.unpack(source, workDir)
            val m = unpacked.manifest
            ImportResult.Success(
                ImportedPluginInfo(
                    id = m.id,
                    name = m.name,
                    version = m.version,
                    description = m.description,
                    main = m.main
                )
            )
        } catch (e: SuicjException) {
            ImportResult.Rejected(e.message ?: "插件校验失败")
        } catch (e: Exception) {
            ImportResult.Rejected("导入失败：${e.message ?: e::class.simpleName}")
        }
    }
}
