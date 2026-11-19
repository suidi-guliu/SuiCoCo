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
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * 对应 06:00 巡检日报 P0「阶段6 收尾」：提供第三方 .suicj 导入入口与导入结果/拒绝原因展示，
 * 以及官方仓库来源说明。文件选择器由宿主（app 层，已具备 activity-compose 依赖）通过
 * [onPickFile] 回调注入，本 Composable 只负责渲染状态，保持 feature 层职责单一。
 *
 * 官方仓库地址的解析与校验见 feature:settings 与 core:config 的 PluginRepositoryConfig。
 */
@Composable
fun MarketplaceScreen(
    importState: ImportController.ImportResult?,
    onPickFile: () -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VscodeTheme.SideBarBg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text(
            text = "扩展",
            color = VscodeTheme.Fg,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))

        // 官方来源说明
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(VscodeTheme.WidgetBg, RoundedCornerShape(4.dp))
                .padding(10.dp)
        ) {
            Text("官方插件", color = VscodeTheme.Fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                "从项目仓库 release/ 获取，设置中配置仓库地址。",
                color = VscodeTheme.FgMuted, fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(12.dp))

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

        // 导入结果反馈
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
