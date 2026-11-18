package com.suicoco.feature.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suicoco.core.design.VscodeTheme

/**
 * 图形化安装向导 —— VSCode Dark Modern 风格。
 *
 * 真实对接安装流程（下载 → 解压 → apt 更新 → 工具链安装），逐行回传日志；
 * 取代早期"点击即完成"的模拟行为。状态与逻辑见 [InstallerController]。
 */
@Composable
fun OnboardingWizard(
    controller: InstallerController,
    modifier: Modifier = Modifier
) {
    val state by controller.state.collectAsState()
    val listState = rememberLazyListState()

    // 有新日志时自动滚动到底部
    LaunchedEffect(state.log.size) {
        if (state.log.isNotEmpty()) listState.animateScrollToItem(state.log.size - 1)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(VscodeTheme.EditorBg)
            .padding(20.dp)
    ) {
        Text(
            text = "环境安装向导",
            color = VscodeTheme.Fg,
            style = MaterialTheme.typography.headlineSmall
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Ubuntu 24.04 LTS · ARM64 · 官方源 · 无需 host root",
            color = VscodeTheme.FgMuted,
            fontSize = 13.sp
        )
        Spacer(Modifier.height(16.dp))

        // 步骤 + 进度
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = state.step.label,
                color = when (state.step) {
                    InstallStep.DONE -> VscodeTheme.Green
                    InstallStep.FAILED -> VscodeTheme.Red
                    InstallStep.CANCELLED -> VscodeTheme.Yellow
                    else -> VscodeTheme.Accent
                },
                fontSize = 15.sp
            )
            Spacer(Modifier.weight(1f))
            if (state.running && state.step == InstallStep.DOWNLOADING) {
                Text(
                    text = "${(state.progress * 100).toInt()}%",
                    color = VscodeTheme.FgMuted,
                    fontSize = 13.sp
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        val progressValue = when {
            state.step == InstallStep.DONE -> 1f
            state.step == InstallStep.DOWNLOADING -> state.progress
            state.running -> 0.5f
            else -> 0f
        }
        LinearProgressIndicator(
            progress = progressValue,
            modifier = Modifier.fillMaxWidth(),
            color = when (state.step) {
                InstallStep.DONE -> VscodeTheme.Green
                InstallStep.FAILED -> VscodeTheme.Red
                else -> VscodeTheme.Accent
            },
            trackColor = VscodeTheme.WidgetBg
        )

        state.error?.let { err ->
            Spacer(Modifier.height(8.dp))
            Text(text = "错误: $err", color = VscodeTheme.Red, fontSize = 13.sp)
        }

        Spacer(Modifier.height(12.dp))

        // 逐行日志
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .background(VscodeTheme.PanelBg)
                .padding(8.dp)
        ) {
            if (state.log.isEmpty()) {
                Text(
                    text = "点击「开始安装」后将在此逐行显示执行日志…",
                    color = VscodeTheme.FgDim,
                    fontSize = 12.sp
                )
            } else {
                LazyColumn(state = listState) {
                    itemsIndexed(state.log) { _, line ->
                        Text(
                            text = line,
                            color = logColor(line),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp,
                            lineHeight = 18.sp
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 操作按钮
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { controller.start() },
                enabled = !state.running,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VscodeTheme.Accent,
                    contentColor = VscodeTheme.ButtonFg
                )
            ) {
                Text(text = if (state.finished) "重新安装" else "开始安装")
            }
            Button(
                onClick = { controller.cancel() },
                enabled = state.running,
                colors = ButtonDefaults.buttonColors(
                    containerColor = VscodeTheme.WidgetBg,
                    contentColor = VscodeTheme.Fg
                )
            ) {
                Text(text = "取消")
            }
        }

        if (state.installedToolchains.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(
                text = "已安装: ${state.installedToolchains.joinToString("、")}",
                color = VscodeTheme.Green,
                fontSize = 12.sp
            )
        }
    }
}

/** 依据日志语义着色，贴近终端观感。 */
private fun logColor(line: String): Color = when {
    line.contains("失败") || line.contains("error", ignoreCase = true) -> VscodeTheme.Red
    line.startsWith("✓") || line.contains("完成") -> VscodeTheme.Green
    line.startsWith("──") -> VscodeTheme.Yellow
    line.startsWith("执行:") || line.startsWith("开始") -> VscodeTheme.Blue
    else -> VscodeTheme.Fg
}
