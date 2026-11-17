package com.suicoco.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suicoco.core.design.VscodeTheme
import com.suicoco.viewmodel.MainViewModel

@Composable
fun TitleBar(vm: MainViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth().height(VscodeTheme.TITLE_BAR_H.dp)
            .background(VscodeTheme.TitleBarBg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(12.dp))
        // 应用标识（菜单按钮）
        Icon(Icons.Default.Menu, "菜单", tint = VscodeTheme.Fg,
            modifier = Modifier.size(16.dp).clickable { })
        Spacer(Modifier.width(10.dp))
        Text("suicoco", color = VscodeTheme.Fg, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Text("  —  SUICOCO-DEMO", color = VscodeTheme.FgMuted, fontSize = 12.sp)

        Spacer(Modifier.weight(1f))

        // 命令面板搜索框（复刻 VSCode 标题栏中央搜索）
        Row(
            modifier = Modifier.widthIn(max = 420.dp).height(22.dp)
                .background(VscodeTheme.InputBg)
                .clickable { vm.openQuick(MainViewModel.QuickMode.COMMAND) }
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Search, "搜索", tint = VscodeTheme.FgMuted,
                modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(6.dp))
            Text("suicoco", color = VscodeTheme.FgDim, fontSize = 11.sp,
                modifier = Modifier.weight(1f))
        }

        Spacer(Modifier.weight(1f))
        Icon(Icons.Default.Remove, "最小化", tint = VscodeTheme.FgMuted,
            modifier = Modifier.size(16.dp).clickable { })
        Spacer(Modifier.width(14.dp))
        Icon(Icons.Default.CropSquare, "最大化", tint = VscodeTheme.FgMuted,
            modifier = Modifier.size(13.dp).clickable { })
        Spacer(Modifier.width(14.dp))
        Icon(Icons.Default.Close, "关闭", tint = VscodeTheme.FgMuted,
            modifier = Modifier.size(16.dp).clickable { })
        Spacer(Modifier.width(10.dp))
    }
}

@Composable
fun ActivityBar(vm: MainViewModel) {
    val current by vm.activityView.collectAsState()
    Column(
        modifier = Modifier.width(VscodeTheme.ACTIVITY_BAR_W.dp).fillMaxHeight()
            .background(VscodeTheme.ActivityBarBg),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(2.dp))
        ActIcon(Icons.Default.Folder, "资源管理器", current == MainViewModel.ActivityView.EXPLORER) {
            vm.setActivityView(MainViewModel.ActivityView.EXPLORER)
        }
        ActIcon(Icons.Default.Search, "搜索", current == MainViewModel.ActivityView.SEARCH) {
            vm.setActivityView(MainViewModel.ActivityView.SEARCH)
        }
        ActIcon(Icons.Default.Source, "源代码管理", current == MainViewModel.ActivityView.SCM) {
            vm.setActivityView(MainViewModel.ActivityView.SCM)
        }
        ActIcon(Icons.Default.PlayArrow, "运行和调试", current == MainViewModel.ActivityView.DEBUG) {
            vm.setActivityView(MainViewModel.ActivityView.DEBUG)
        }
        ActIcon(Icons.Default.Extension, "扩展", current == MainViewModel.ActivityView.EXTENSIONS) {
            vm.setActivityView(MainViewModel.ActivityView.EXTENSIONS)
        }

        Spacer(Modifier.weight(1f))

        // 安装向导入口（齿轮图标）
        ActIcon(Icons.Default.Settings, "安装向导与设置", false) { vm.openWizard() }
        Spacer(Modifier.height(6.dp))
    }
}

@Composable
private fun ActIcon(icon: ImageVector, desc: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().height(48.dp)
            .background(if (active) VscodeTheme.HoverBg else Color.Transparent)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (active) {
            Box(
                Modifier.width(2.dp).height(48.dp).align(Alignment.CenterStart)
                    .background(VscodeTheme.Fg)
            )
        }
        Icon(icon, desc, tint = if (active) VscodeTheme.Fg else VscodeTheme.FgMuted,
            modifier = Modifier.size(VscodeTheme.ICON_SIZE.dp))
    }
}

@Composable
fun StatusBar(vm: MainViewModel) {
    val tabs by vm.tabs.collectAsState()
    val activePath by vm.activeTabPath.collectAsState()
    val envInstalled by vm.envInstalled.collectAsState()
    val activeTab = tabs.find { it.path == activePath }
    Row(
        modifier = Modifier.fillMaxWidth().height(VscodeTheme.STATUS_BAR_H.dp)
            .background(VscodeTheme.StatusBarBg)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.Code, "远程", tint = VscodeTheme.StatusBarFg,
            modifier = Modifier.size(13.dp).clickable { })
        Spacer(Modifier.width(8.dp))
        Text("suicoco", color = VscodeTheme.StatusBarFg, fontSize = 11.sp)
        Spacer(Modifier.width(16.dp))
        if (activeTab?.language != null) {
            Text(activeTab.language!!.uppercase(), color = VscodeTheme.StatusBarFg, fontSize = 11.sp)
            Spacer(Modifier.width(14.dp))
        }
        Text("UTF-8", color = VscodeTheme.StatusBarFg, fontSize = 11.sp)
        Spacer(Modifier.width(14.dp))
        Text("LF", color = VscodeTheme.StatusBarFg, fontSize = 11.sp)

        Spacer(Modifier.weight(1f))
        Text(
            if (envInstalled.isEmpty()) "⚙ 环境未安装" else "✓ ${envInstalled.size} 个环境就绪",
            color = VscodeTheme.StatusBarFg, fontSize = 11.sp,
            modifier = Modifier.clickable { vm.openWizard() }
        )
        Spacer(Modifier.width(14.dp))
        Text("Ln 1, Col 1", color = VscodeTheme.StatusBarFg, fontSize = 11.sp)
        Spacer(Modifier.width(12.dp))
        Text("⊕", color = VscodeTheme.StatusBarFg, fontSize = 13.sp)
        Spacer(Modifier.width(6.dp))
        Text("🔔", color = VscodeTheme.StatusBarFg, fontSize = 11.sp)
    }
}
