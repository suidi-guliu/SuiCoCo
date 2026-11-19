package com.suicoco.ui.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suicoco.core.design.VscodeTheme
import com.suicoco.ui.panel.QuickInputOverlay
import com.suicoco.ui.panel.PanelArea
import com.suicoco.ui.panel.SideBar
import com.suicoco.ui.editor.EditorArea
import com.suicoco.ui.panel.NotificationHost
import com.suicoco.ui.panel.InstallWizard
import com.suicoco.ui.panel.PluginMarketplaceOverlay
import com.suicoco.viewmodel.MainViewModel

/**
 * suicoco 主工作台 — VSCode 布局骨架。
 * 布局顺序（横向）：活动栏 | 侧边栏 | [编辑器区 + 底部面板]
 * 纵向：标题栏 / 主体 / 状态栏
 */
@Composable
fun SuicocoShell(vm: MainViewModel) {
    val sideBarVisible by vm.sideBarVisible.collectAsState()
    val panelVisible by vm.panelVisible.collectAsState()

    Surface(color = VscodeTheme.EditorBg, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 标题栏
            TitleBar(vm)

            // 主体
            Row(modifier = Modifier.fillMaxSize().weight(1f)) {
                ActivityBar(vm)
                if (sideBarVisible) {
                    SideBar(vm)
                    Box(
                        Modifier.width(1.dp).fillMaxHeight()
                            .background(VscodeTheme.Border)
                    )
                }
                Column(modifier = Modifier.fillMaxSize().weight(1f)) {
                    EditorArea(vm)
                    if (panelVisible) {
                        Box(
                            Modifier.fillMaxWidth().height(1.dp)
                                .background(VscodeTheme.Border)
                        )
                        PanelArea(vm)
                    }
                }
            }

            // 状态栏
            StatusBar(vm)
        }

        // 浮层
        QuickInputOverlay(vm)
        NotificationHost(vm)
        InstallWizard(vm)
        PluginMarketplaceOverlay(vm)
    }
}
