package com.suicoco.ui.panel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.suicoco.core.design.VscodeTheme
import com.suicoco.data.StepStatus
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

/** 图形化安装向导 — Ubuntu 精简系统 + 语言环境（官方源） */
@Composable
fun InstallWizard(vm: MainViewModel) {
    val visible by vm.wizardVisible.collectAsState()
    if (!visible) return
    val steps by vm.installSteps.collectAsState()

    Dialog(onDismissRequest = { vm.closeWizard() }) {
        Column(
            modifier = Modifier.fillMaxWidth().wrapContentHeight()
                .background(VscodeTheme.SideBarBg, RoundedCornerShape(8.dp))
                .padding(20.dp)
        ) {
            Text("环境安装向导", color = VscodeTheme.Fg, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text("图形化安装 Ubuntu 精简系统与语言环境（均使用官方源）",
                color = VscodeTheme.FgMuted, fontSize = 12.sp)
            Spacer(Modifier.height(16.dp))

            // 来源徽标
            Row(modifier = Modifier.fillMaxWidth().background(VscodeTheme.WidgetBg, RoundedCornerShape(4.dp))
                .padding(10.dp)) {
                Text("🔒 系统: archive.ubuntu.com 官方源", color = VscodeTheme.Green, fontSize = 11.sp)
            }
            Spacer(Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth().background(VscodeTheme.WidgetBg, RoundedCornerShape(4.dp))
                .padding(10.dp)) {
                Text("🔒 语言环境: Ubuntu apt 官方源", color = VscodeTheme.Green, fontSize = 11.sp)
            }
            Spacer(Modifier.height(16.dp))

            steps.forEach { step ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)
                        .background(VscodeTheme.WidgetBg, RoundedCornerShape(4.dp))
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val (icon, tint) = when (step.status) {
                        StepStatus.DONE -> "✓" to VscodeTheme.Green
                        StepStatus.RUNNING -> "◐" to VscodeTheme.Yellow
                        StepStatus.FAILED -> "✕" to VscodeTheme.Red
                        StepStatus.PENDING -> "○" to VscodeTheme.FgDim
                    }
                    Text(icon, color = tint, fontSize = 18.sp)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(step.title, color = VscodeTheme.Fg, fontSize = 13.sp)
                        Text(step.subtitle, color = VscodeTheme.FgMuted, fontSize = 11.sp)
                    }
                    if (step.status == StepStatus.PENDING || step.status == StepStatus.FAILED) {
                        Text("安装", color = VscodeTheme.Link, fontSize = 12.sp,
                            modifier = Modifier.clickable {
                                vm.setStepRunning(step.id)
                                // 桌面原型：模拟安装完成；真实执行由 proot 运行时对接
                                vm.completeStep(step.id)
                            }.padding(8.dp))
                    } else if (step.status == StepStatus.RUNNING) {
                        Text("安装中...", color = VscodeTheme.Yellow, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("关闭", color = VscodeTheme.FgMuted, fontSize = 13.sp,
                    modifier = Modifier.clickable { vm.closeWizard() }.padding(10.dp))
            }
        }
    }
}
