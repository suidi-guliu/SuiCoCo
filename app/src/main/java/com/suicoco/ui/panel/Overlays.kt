package com.suicoco.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.suicoco.core.design.VscodeTheme
import com.suicoco.data.StepStatus
import com.suicoco.feature.onboarding.InstallStep
import com.suicoco.feature.onboarding.InstallerController
import com.suicoco.feature.onboarding.OnboardingWizard
import com.suicoco.viewmodel.MainViewModel

/** 右下角通知 */
@Composable
fun NotificationHost(vm: MainViewModel) {
    val notification = vm.notification.collectAsState().value ?: return
    val (type, message) = notification
    val accent = when (type) {
        "error" -> VscodeTheme.Red
        "warn" -> VscodeTheme.Yellow
        "success" -> VscodeTheme.Green
        else -> VscodeTheme.Accent
    }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomEnd) {
        Row(
            modifier = Modifier.padding(16.dp).widthIn(max = 380.dp)
                .background(VscodeTheme.WidgetBg, RoundedCornerShape(4.dp))
                .clickable { vm.clearNotification() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.width(3.dp).height(32.dp).background(accent))
            Spacer(Modifier.width(10.dp))
            Text(message, color = VscodeTheme.Fg, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Icon(Icons.Default.Close, "关闭", tint = VscodeTheme.FgMuted,
                modifier = Modifier.size(14.dp).clickable { vm.clearNotification() })
        }
    }
}

/** 图形化安装向导 — 对接真实 proot 执行（下载 → 解压 → apt → 工具链），逐行回传日志 */
@Composable
fun InstallWizard(vm: MainViewModel) {
    val visible by vm.wizardVisible.collectAsState()
    if (!visible) return
    val context = LocalContext.current
    // 向导生命周期内复用同一控制器，保证取消/状态一致
    // v0.3.11：注入 vm 共享的 PTY 会话执行器，向导与终端复用同一套 fail-closed 会话
    val controller = remember { InstallerController(context, vm.installerExecutor) }
    val state by controller.state.collectAsState()

    // 安装完成后，把结果同步回主状态机（环境守卫放行 + 各步骤标记 DONE）
    LaunchedEffect(state.step) {
        if (state.step == InstallStep.DONE) {
            vm.completeStep("rootfs")
            state.installedToolchains.forEach { id -> vm.completeStep("toolchain-$id") }
        }
    }

    Dialog(onDismissRequest = { if (!state.running) vm.closeWizard() }) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = VscodeTheme.SideBarBg,
            modifier = Modifier.fillMaxWidth().height(560.dp)
        ) {
            Box(Modifier.fillMaxSize()) {
                OnboardingWizard(controller = controller)
                // 右上角关闭（运行中禁止关闭，避免误触中断）
                Icon(
                    Icons.Default.Close, "关闭", tint = VscodeTheme.FgMuted,
                    modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                        .clickable { if (!state.running) vm.closeWizard() }
                )
            }
        }
    }
}
