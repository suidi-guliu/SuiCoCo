package com.suicoco.feature.marketplace

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suicoco.core.design.VscodeTheme

/**
 * 扩展 / 插件市场面板 —— VSCode Dark Modern 风格。
 *
 * 对应 06:00 巡检日报 P0「阶段6 收尾」提供第三方 .suicj 导入入口；本轮（2026-11-20 06:03 日报
 * P0 后续余项）在其上接入「官方插件清单 + 一键下载 + 自动安装」交互：
 *  - [catalogState] 为清单加载结果（成功含可安装条目、失败含原因），由宿主用
 *    [OfficialInstallController.loadCatalog] 预热；
 *  - 每个引擎兼容的官方条目展示名称/版本/描述/依赖环境，并提供「一键安装」按钮，
 *    点击回调 [onInstall]，真正下载 / 校验 / 导入由宿主 Overlay 在后台完成；
 *  - [installState] / [installError] 展示最近一次一键安装反馈。
 *
 * 本 Composable 只负责渲染状态；文件选择器、网络 I/O 与安装编排由宿主 app 层注入，
 * 保持 feature 层职责单一。官方地址解析与校验见 core:config 的 PluginRepositoryConfig / PluginCatalog。
 */
@Composable
fun MarketplaceScreen(
    catalogState: OfficialInstallController.CatalogResult?,
    installState: OfficialInstallController.VerifyResult?,
    installError: String?,
    busyInstalling: String?,
    onInstall: (OfficialInstallController.CatalogItem) -> Unit,
    onRefreshCatalog: () -> Unit,
    importState: ImportController.ImportResult?,
    onPickFile: () -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VscodeTheme.SideBarBg)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "扩展",
                color = VscodeTheme.Fg,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                "刷新清单",
                color = VscodeTheme.Link,
                fontSize = 11.sp,
                modifier = Modifier.clickable { onRefreshCatalog() }
            )
        }
        Spacer(Modifier.height(8.dp))

        // 官方插件清单（一键下载 + 自动安装）
        when (val cat = catalogState) {
            null -> Text("正在加载官方插件清单…", color = VscodeTheme.FgMuted, fontSize = 11.sp)
            is OfficialInstallController.CatalogResult.Failure -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(VscodeTheme.WidgetBg, RoundedCornerShape(4.dp))
                        .padding(10.dp)
                ) {
                    Text("✗ 清单加载失败", color = VscodeTheme.Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(cat.reason, color = VscodeTheme.Orange, fontSize = 10.sp)
                }
            }
            is OfficialInstallController.CatalogResult.Success -> {
                Text(
                    "官方插件 · 清单 v${cat.version}（${cat.items.size}）",
                    color = VscodeTheme.FgMuted, fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                cat.items.forEach { item ->
                    OfficialPluginCard(item, busyInstalling, onInstall)
                    Spacer(Modifier.height(6.dp))
                }
            }
        }

        // 一键安装反馈
        installError?.let { err ->
            Spacer(Modifier.height(6.dp))
            Text("✗ $err", color = VscodeTheme.Orange, fontSize = 10.sp,
                style = MaterialTheme.typography.bodySmall)
        }
        installState?.let { res ->
            Spacer(Modifier.height(6.dp))
            when (res) {
                is OfficialInstallController.VerifyResult.Ok ->
                    Text("✓ ${res.item.name} 下载校验通过，正在安装…", color = VscodeTheme.Green, fontSize = 10.sp)
                is OfficialInstallController.VerifyResult.Fail ->
                    Text("✗ ${res.reason}", color = VscodeTheme.Orange, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(14.dp))

        // 第三方导入入口
        Text("从本地安装", color = VscodeTheme.FgMuted, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onPickFile,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = VscodeTheme.Accent,
                contentColor = VscodeTheme.ButtonFg
            )
        ) {
            Text("📥 导入第三方 .suicj", fontSize = 12.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "导入时执行完整安全校验（zip-slip / 解压炸弹 / 引擎兼容），失败即拒绝并说明原因。",
            color = VscodeTheme.FgDim, fontSize = 10.sp,
            style = MaterialTheme.typography.bodySmall
        )

        Spacer(Modifier.height(14.dp))

        // 第三方导入结果反馈
        importState?.let { result ->
            when (result) {
                is ImportController.ImportResult.Success -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(VscodeTheme.WidgetBg, RoundedCornerShape(4.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✓", color = VscodeTheme.Green, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Text("导入成功", color = VscodeTheme.Green, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("清除", color = VscodeTheme.Link, fontSize = 11.sp,
                                modifier = Modifier.clickable { onDismissResult() })
                        }
                        Spacer(Modifier.height(6.dp))
                        Text("${result.plugin.name}  v${result.plugin.version}", color = VscodeTheme.Fg, fontSize = 11.sp)
                        Text("id: ${result.plugin.id}", color = VscodeTheme.FgMuted, fontSize = 10.sp)
                    }
                }
                is ImportController.ImportResult.Rejected -> {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(VscodeTheme.WidgetBg, RoundedCornerShape(4.dp))
                            .padding(10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("✗", color = VscodeTheme.Red, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(6.dp))
                            Text("导入被拒绝", color = VscodeTheme.Red, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("清除", color = VscodeTheme.Link, fontSize = 11.sp,
                                modifier = Modifier.clickable { onDismissResult() })
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(result.reason, color = VscodeTheme.Orange, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/** 单条官方插件卡片：展示元信息 + 一键安装按钮；不兼容/安装中禁用按钮。 */
@Composable
private fun OfficialPluginCard(
    item: OfficialInstallController.CatalogItem,
    busyInstalling: String?,
    onInstall: (OfficialInstallController.CatalogItem) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(VscodeTheme.WidgetBg, RoundedCornerShape(4.dp))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.name, color = VscodeTheme.Fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text("v${item.version}", color = VscodeTheme.FgMuted, fontSize = 10.sp)
            Spacer(Modifier.weight(1f))
            when {
                !item.compatible -> Text("引擎不兼容", color = VscodeTheme.Orange, fontSize = 10.sp)
                busyInstalling == item.file -> Text("安装中…", color = VscodeTheme.Link, fontSize = 10.sp)
                else -> Text(
                    "一键安装",
                    color = VscodeTheme.Link, fontSize = 11.sp,
                    modifier = Modifier.clickable { onInstall(item) }
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(item.description, color = VscodeTheme.FgMuted, fontSize = 10.sp,
            style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(2.dp))
        val env = item.requiresEnv?.let { " · 需环境: $it" } ?: ""
        Text("id: ${item.id} · engine ${item.engine}$env", color = VscodeTheme.FgDim, fontSize = 9.sp)
    }
}
