package com.suicoco.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suicoco.core.design.VscodeTheme

/**
 * 设置面板 —— 官方插件仓库地址配置（VSCode Dark Modern 风格）。
 *
 * 对应 06:00 巡检日报 P0「阶段6 收尾——官方插件仓库地址配置项」及 18:00 日报 P0
 *「阶段6 后续——仓库地址跨会话持久化」。输入实时经 [RepositoryConfigController]
 *（复用 core:config 的 PluginRepositoryConfig）做 fail-closed 校验：非法地址显示原因且
 * 拒绝保存，合法地址显示规范化结果与插件清单路径；点「保存」经 [RepositoryConfigStore]
 * 落盘，跨会话（杀进程重启）后由 [RepositoryConfigStore.load] 经白名单净化后回填。
 */
@Composable
fun SettingsScreen(
    onOpenWizard: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val store = remember { RepositoryConfigStore(context) }
    var input by remember { mutableStateOf(store.load()) }
    var saved by remember { mutableStateOf(false) }
    val result = remember(input) { RepositoryConfigController.validate(input) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VscodeTheme.SideBarBg)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Text("设置", color = VscodeTheme.Fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))

        Text("插件仓库地址", color = VscodeTheme.Fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "仅允许 https 官方白名单域名，非法地址将被拒绝（fail-closed）。",
            color = VscodeTheme.FgDim, fontSize = 10.sp
        )
        Spacer(Modifier.height(8.dp))

        // 输入框
        BasicTextField(
            value = input,
            onValueChange = { input = it; saved = false },
            modifier = Modifier
                .fillMaxWidth()
                .background(VscodeTheme.InputBg, RoundedCornerShape(4.dp))
                .padding(10.dp),
            textStyle = TextStyle(
                color = VscodeTheme.Fg,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            ),
            singleLine = true,
            cursorBrush = SolidColor(VscodeTheme.FocusBorder)
        )

        Spacer(Modifier.height(8.dp))

        when (result) {
            is RepositoryConfigController.FieldResult.Ok -> {
                Text("✓ 地址合法", color = VscodeTheme.Green, fontSize = 11.sp)
                Spacer(Modifier.height(4.dp))
                Text(
                    "规范化：${result.normalizedUrl}",
                    color = VscodeTheme.FgMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    "清单：${result.catalogUrl}",
                    color = VscodeTheme.FgMuted, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                )
            }
            is RepositoryConfigController.FieldResult.Error -> {
                Text("✗ ${result.reason}", color = VscodeTheme.Red, fontSize = 11.sp)
            }
        }

        Spacer(Modifier.height(6.dp))
        if (saved) {
            Text("✓ 已保存，将在重启后生效", color = VscodeTheme.Green, fontSize = 10.sp)
            Spacer(Modifier.height(6.dp))
        }
        Button(
            onClick = {
                val ok = result as? RepositoryConfigController.FieldResult.Ok
                if (ok != null && store.save(ok.normalizedUrl)) saved = true
            },
            enabled = result is RepositoryConfigController.FieldResult.Ok,
            colors = ButtonDefaults.buttonColors(
                containerColor = VscodeTheme.Accent,
                contentColor = VscodeTheme.ButtonFg
            )
        ) {
            Text("保存", fontSize = 12.sp)
        }

        Spacer(Modifier.height(18.dp))
        Text("环境", color = VscodeTheme.Fg, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Button(
            onClick = onOpenWizard,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = VscodeTheme.WidgetBg,
                contentColor = VscodeTheme.Fg
            )
        ) {
            Text("打开环境安装向导", fontSize = 12.sp)
        }
    }
}
